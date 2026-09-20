package com.miniredis.server;

import com.miniredis.command.CommandHandler;

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

/**
 * Single-client RESP connection handler.
 *
 * Hardening:
 *  - QUIT closes the connection after reply
 *  - Protocol errors return -ERR when possible instead of silent drop
 *  - Stricter size limits
 *  - Cleaner disconnect paths
 */
public final class ClientHandler implements Runnable {

    private static final int MAX_BULK_LENGTH   = 512 * 1024;  // 512 KB
    private static final int MAX_ARRAY_LENGTH  = 1024;
    private static final int MAX_INLINE_LENGTH = 64 * 1024;
    private static final int MAX_NESTING       = 1;           // commands are flat arrays only

    private final Socket client;
    private final CommandHandler handler;

    public ClientHandler(Socket client, CommandHandler handler) {
        this.client = client;
        this.handler = handler;
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
                    // Recoverable protocol error → reply and continue
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

                String name = command.get(0);
                log("[" + remote + "] " + name + (command.size() > 1 ? " ..." : ""));

                String response = handler.handle(command);
                writeResponse(out, response);
                out.flush();

                // QUIT → close after successful reply
                if (name.equalsIgnoreCase("QUIT")) {
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

    // ──────────────────────────────────────────────────────────────
    // RESP Reader
    // ──────────────────────────────────────────────────────────────

    /**
     * @return command args, empty list for blank inline, null on EOF
     */
    private List<String> readCommand(InputStream in) throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }

        if (first == '*') {
            return readArray(in);
        }

        // Allow inline for telnet / simple tests
        if (first == '+' || first == '-' || first == ':' || first == '$') {
            // Client sent a response type as request — invalid
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

        long len = readLong(in);
        if (len == -1) {
            return null;
        }
        if (len < 0) {
            throw new ProtocolException("Protocol error: invalid bulk length");
        }
        if (len > MAX_BULK_LENGTH) {
            throw new ProtocolException("Protocol error: bulk length too large");
        }

        byte[] buf = new byte[(int) len];
        int offset = 0;
        while (offset < len) {
            int n = in.read(buf, offset, (int) (len - offset));
            if (n == -1) {
                throw new EOFException("Unexpected EOF in bulk");
            }
            offset += n;
        }
        expectCrlf(in);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private List<String> readInline(InputStream in, char first) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        sb.append(first);

        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > MAX_INLINE_LENGTH) {
                throw new ProtocolException("Protocol error: inline request too long");
            }
        }

        String line = sb.toString().trim();
        if (line.isEmpty()) {
            return List.of();
        }

        // Simple whitespace split (telnet only; real clients use RESP arrays)
        String[] parts = line.split("\\s+");
        List<String> args = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) args.add(p);
        }
        return args;
    }

    private long readLong(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(20);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int n = in.read();
                if (n != '\n') {
                    throw new ProtocolException("Protocol error: expected LF after CR");
                }
                break;
            }
            if (b == '\n') {
                break; // tolerate lone LF
            }
            sb.append((char) b);
            if (sb.length() > 20) {
                throw new ProtocolException("Protocol error: number too long");
            }
        }
        if (sb.isEmpty()) {
            throw new ProtocolException("Protocol error: empty number");
        }
        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            throw new ProtocolException("Protocol error: invalid number");
        }
    }

    private void expectCrlf(InputStream in) throws IOException {
        int c1 = in.read();
        int c2 = in.read();
        if (c1 == -1 || c2 == -1) {
            throw new EOFException();
        }
        if (c1 != '\r' || c2 != '\n') {
            throw new ProtocolException("Protocol error: expected CRLF");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Writer
    // ──────────────────────────────────────────────────────────────

    private void writeResponse(OutputStream out, String resp) throws IOException {
        if (resp == null || resp.isEmpty()) {
            out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        out.write(resp.getBytes(StandardCharsets.UTF_8));
    }

    private static String error(String msg) {
        return "-ERR " + msg + "\r\n";
    }

    // ──────────────────────────────────────────────────────────────
    // Protocol error type (recoverable)
    // ──────────────────────────────────────────────────────────────

    private static final class ProtocolException extends IOException {
        ProtocolException(String message) {
            super(message);
        }
    }

    private static void log(String msg) {
        System.out.println("[miniredis] " + msg);
    }
}