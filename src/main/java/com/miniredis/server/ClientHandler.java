package com.miniredis.server;

import com.miniredis.command.CommandHandler;
import com.miniredis.security.ApiKeyService;
import com.miniredis.security.SecurityContext;
import com.miniredis.storage.EmbeddedStorageEngine;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.TenantStorageEngine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Single-client RESP connection handler supporting multi-tenant AUTH routing.
 */
public final class ClientHandler implements Runnable {

    private static final int MAX_BULK_LENGTH   = 512 * 1024;  // 512 KB
    private static final int MAX_ARRAY_LENGTH  = 1024;
    private static final int MAX_INLINE_LENGTH = 64 * 1024;

    private final Socket client;
    private final StorageEngine rootEngine;
    private final ApiKeyService apiKeyService;
    private final boolean requireAuth;

    private CommandHandler sessionHandler;
    private SecurityContext sessionContext;

    public ClientHandler(Socket client, CommandHandler handler) {
        this(client, handler.getStore(), null, false);
    }

    public ClientHandler(Socket client, StorageEngine rootEngine, ApiKeyService apiKeyService, boolean requireAuth) {
        this.client = client;
        this.rootEngine = rootEngine;
        this.apiKeyService = apiKeyService;
        this.requireAuth = requireAuth;
        this.sessionHandler = new CommandHandler(rootEngine, apiKeyService);
        this.sessionContext = null;
    }

    @Override
    public void run() {
        final String remote = String.valueOf(client.getRemoteSocketAddress());
        log("Connected: " + remote + " | " + Thread.currentThread().getName());

        try (
                Socket socket = client;
                InputStream rawIn = socket.getInputStream();
                OutputStream rawOut = socket.getOutputStream();
                BufferedInputStream in = new BufferedInputStream(rawIn, 8192);
                BufferedOutputStream out = new BufferedOutputStream(rawOut, 8192)
        ) {
            boolean running = true;

            while (running && !socket.isClosed()) {
                List<String> command;
                try {
                    command = readCommand(in);
                } catch (ProtocolException pe) {
                    writeResponse(out, error(pe.getMessage()));
                    out.flush();
                    continue;
                }

                if (command == null) {
                    break; // client closed
                }
                if (command.isEmpty()) {
                    continue;
                }

                String name = command.get(0).toUpperCase(Locale.ROOT);
                log("[" + remote + "] " + name + (command.size() > 1 ? " ..." : ""));

                // Handle multi-tenant AUTH command specifically
                if (name.equals("AUTH")) {
                    handleAuthCommand(command, out);
                    out.flush();
                    continue;
                }

                // Enforce AUTH if configured
                if (requireAuth && sessionContext == null) {
                    if (name.equals("PING") || name.equals("QUIT") || name.equals("COMMAND")) {
                        String resp = sessionHandler.handle(command);
                        writeResponse(out, resp);
                        out.flush();
                        if (name.equals("QUIT")) running = false;
                        continue;
                    }
                    writeResponse(out, "-NOAUTH Authentication required.\r\n");
                    out.flush();
                    continue;
                }

                String response = sessionHandler.handle(command);
                writeResponse(out, response);
                out.flush();

                if (name.equals("QUIT")) {
                    running = false;
                }
            }

        } catch (SocketTimeoutException e) {
            log("Timeout: " + remote);
        } catch (EOFException | SocketException e) {
            // normal disconnect
        } catch (IOException e) {
            log("I/O error from " + remote + ": " + e.getMessage());
        } catch (Exception e) {
            log("Unexpected error from " + remote + ": " + e.getMessage());
        } finally {
            log("Disconnected: " + remote);
        }
    }

    private void handleAuthCommand(List<String> command, OutputStream out) throws IOException {
        if (command.size() < 2 || command.size() > 3) {
            writeResponse(out, error("wrong number of arguments for 'auth' command"));
            return;
        }

        String password = command.size() == 2 ? command.get(1) : command.get(2);

        if (apiKeyService == null) {
            writeResponse(out, "+OK\r\n");
            return;
        }

        Optional<SecurityContext> maybeCtx = apiKeyService.authenticate(password);
        if (maybeCtx.isPresent()) {
            this.sessionContext = maybeCtx.get();
            // Bind this client session to the tenant's isolated engine!
            TenantStorageEngine tenantStore = new TenantStorageEngine(rootEngine, sessionContext.projectId(), 0);
            this.sessionHandler = new CommandHandler(tenantStore, apiKeyService);
            writeResponse(out, "+OK\r\n");
            log("Client authenticated for project: " + sessionContext.projectId());
        } else {
            writeResponse(out, "-WRONGPASS invalid username-password pair or user is disabled.\r\n");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // RESP Reader
    // ──────────────────────────────────────────────────────────────

    private List<String> readCommand(InputStream in) throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }

        if (first == '*') {
            return readArray(in);
        }

        if (first == '+' || first == '-' || first == ':' || first == '$') {
            throw new ProtocolException("Protocol error: invalid request type");
        }

        return readInline(in, (char) first);
    }

    private List<String> readArray(InputStream in) throws IOException {
        long count = readLong(in);
        if (count < 0) {
            throw new ProtocolException("Protocol error: invalid multibulk length");
        }
        if (count > MAX_ARRAY_LENGTH) {
            throw new ProtocolException("Protocol error: multibulk length too large");
        }

        List<String> args = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            args.add(readBulkString(in));
        }
        return args;
    }

    private String readBulkString(InputStream in) throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new EOFException();
        }
        if (type != '$') {
            throw new ProtocolException("Protocol error: expected '$', got '" + (char) type + "'");
        }

        long length = readLong(in);
        if (length < -1) {
            throw new ProtocolException("Protocol error: invalid bulk length");
        }
        if (length > MAX_BULK_LENGTH) {
            throw new ProtocolException("Protocol error: bulk string length exceeds limit");
        }
        if (length == -1) {
            return null;
        }

        byte[] bytes = in.readNBytes((int) length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected EOF while reading bulk string");
        }

        expectCrLf(in);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private List<String> readInline(InputStream in, char first) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(first);

        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') break;
                if (next != -1) sb.append('\r').append((char) next);
            } else if (b == '\n') {
                break;
            } else {
                if (sb.length() >= MAX_INLINE_LENGTH) {
                    throw new ProtocolException("Protocol error: inline command too long");
                }
                sb.append((char) b);
            }
        }

        String line = sb.toString().trim();
        if (line.isEmpty()) return List.of();

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    private long readLong(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next != '\n') throw new ProtocolException("Protocol error: expected '\\n' after '\\r'");
                break;
            }
            if (b == '\n') break;
            sb.append((char) b);
        }
        try {
            return Long.parseLong(sb.toString().trim());
        } catch (NumberFormatException e) {
            throw new ProtocolException("Protocol error: invalid integer '" + sb + "'");
        }
    }

    private void expectCrLf(InputStream in) throws IOException {
        int r = in.read();
        int n = in.read();
        if (r != '\r' || n != '\n') {
            throw new ProtocolException("Protocol error: expected CRLF");
        }
    }

    private void writeResponse(OutputStream out, String response) throws IOException {
        out.write(response.getBytes(StandardCharsets.UTF_8));
    }

    private String error(String message) {
        return "-ERR " + message + "\r\n";
    }

    private void log(String msg) {
        System.out.println("[ClientHandler] " + msg);
    }

    public static final class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }
}
