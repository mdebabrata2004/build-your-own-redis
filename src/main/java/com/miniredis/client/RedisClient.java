package com.miniredis.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Production-ready RESP client for Mini Redis.
 */
public final class RedisClient implements AutoCloseable {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6379;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final String host;
    private final int port;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public RedisClient() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public RedisClient(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host cannot be empty");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        this.host = host;
        this.port = port;
    }

    // ──────────────────────────────────────────────────────────────
    // Connection management
    // ──────────────────────────────────────────────────────────────

    public synchronized void connect() throws IOException {
        if (isConnected()) {
            return;
        }

        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setSoTimeout(READ_TIMEOUT_MS);

            this.socket = s;
            this.in = new BufferedInputStream(s.getInputStream(), 8192);
            this.out = new BufferedOutputStream(s.getOutputStream(), 8192);
        } catch (IOException e) {
            closeQuietly(s);
            throw new IOException("Could not connect to " + host + ":" + port, e);
        }
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized void disconnect() {
        closeQuietly(in);
        closeQuietly(out);
        closeQuietly(socket);
        in = null;
        out = null;
        socket = null;
    }

    @Override
    public void close() {
        disconnect();
    }

    // ──────────────────────────────────────────────────────────────
    // Low-level command execution
    // ──────────────────────────────────────────────────────────────

    /**
     * Sends a command and returns the raw RESP response as a String.
     * Example: execute("SET", "name", "Grok")
     */
    public synchronized String execute(String... args) throws IOException {
        ensureConnected();
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("Command cannot be empty");
        }

        writeCommand(args);
        return readResponse();
    }

    /**
     * Same as execute() but returns a typed Object:
     * - String for bulk / simple string
     * - Long for integer
     * - List for array
     * - null for null bulk
     */
    public synchronized Object executeTyped(String... args) throws IOException {
        ensureConnected();
        writeCommand(args);
        return readTypedResponse();
    }

    // ──────────────────────────────────────────────────────────────
    // Convenience methods (most used Redis commands)
    // ──────────────────────────────────────────────────────────────

    public String ping() throws IOException {
        Object res = executeTyped("PING");
        return res == null ? null : res.toString();
    }

    public String set(String key, String value) throws IOException {
        Object res = executeTyped("SET", key, value);
        return res == null ? null : res.toString();
    }

    public String set(String key, String value, long ttlSeconds) throws IOException {
        Object res = executeTyped("SET", key, value, "EX", String.valueOf(ttlSeconds));
        return res == null ? null : res.toString();
    }

    public String setnx(String key, String value) throws IOException {
        Object res = executeTyped("SET", key, value, "NX");
        return res == null ? null : res.toString();
    }

    public String get(String key) throws IOException {
        Object res = executeTyped("GET", key);
        return res == null ? null : res.toString();
    }

    public long del(String... keys) throws IOException {
        String[] cmd = new String[keys.length + 1];
        cmd[0] = "DEL";
        System.arraycopy(keys, 0, cmd, 1, keys.length);
        Object res = executeTyped(cmd);
        return res instanceof Long l ? l : 0L;
    }

    public boolean exists(String key) throws IOException {
        Object res = executeTyped("EXISTS", key);
        return res instanceof Long l && l > 0;
    }

    public boolean expire(String key, long seconds) throws IOException {
        Object res = executeTyped("EXPIRE", key, String.valueOf(seconds));
        return res instanceof Long l && l == 1;
    }

    public long ttl(String key) throws IOException {
        Object res = executeTyped("TTL", key);
        return res instanceof Long l ? l : -2L;
    }

    public long incr(String key) throws IOException {
        Object res = executeTyped("INCR", key);
        return res instanceof Long l ? l : 0L;
    }

    public long incrBy(String key, long delta) throws IOException {
        Object res = executeTyped("INCRBY", key, String.valueOf(delta));
        return res instanceof Long l ? l : 0L;
    }

    public long decr(String key) throws IOException {
        Object res = executeTyped("DECR", key);
        return res instanceof Long l ? l : 0L;
    }

    public List<String> keys(String pattern) throws IOException {
        Object res = executeTyped("KEYS", pattern);
        if (res instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object o : list) {
                result.add(o == null ? null : o.toString());
            }
            return result;
        }
        return List.of();
    }

    public String flushDb() throws IOException {
        Object res = executeTyped("FLUSHDB");
        return res == null ? null : res.toString();
    }

    public long dbSize() throws IOException {
        Object res = executeTyped("DBSIZE");
        return res instanceof Long l ? l : 0L;
    }

    public String info() throws IOException {
        Object res = executeTyped("INFO");
        return res == null ? null : res.toString();
    }

    // ──────────────────────────────────────────────────────────────
    // RESP Writer
    // ──────────────────────────────────────────────────────────────

    private void writeCommand(String... args) throws IOException {
        // *<count>\r\n
        out.write(('*'+"").getBytes(StandardCharsets.UTF_8));
        out.write(String.valueOf(args.length).getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));

        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            // $<len>\r\n
            out.write('$');
            out.write(String.valueOf(bytes.length).getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            // <data>\r\n
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    // ──────────────────────────────────────────────────────────────
    // RESP Reader
    // ──────────────────────────────────────────────────────────────

    private String readResponse() throws IOException {
        Object typed = readTypedResponse();
        if (typed == null) {
            return null;
        }
        if (typed instanceof List<?> list) {
            return list.toString();
        }
        return typed.toString();
    }

    private Object readTypedResponse() throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new IOException("Server closed the connection");
        }

        return switch ((char) type) {
            case '+' -> readSimpleString();
            case '-' -> {
                String msg = readLine();
                throw new RedisException(msg);
            }
            case ':' -> Long.parseLong(readLine());
            case '$' -> readBulkString();
            case '*' -> readArray();
            default -> throw new IOException("Unknown RESP type: " + (char) type);
        };
    }

    private String readSimpleString() throws IOException {
        return readLine();
    }

    private String readBulkString() throws IOException {
        long len = Long.parseLong(readLine());
        if (len == -1) {
            return null;
        }
        if (len < 0) {
            throw new IOException("Invalid bulk length: " + len);
        }
        if (len > 512 * 1024) { // safety
            throw new IOException("Bulk string too large");
        }

        byte[] buf = new byte[(int) len];
        int offset = 0;
        while (offset < len) {
            int n = in.read(buf, offset, (int) (len - offset));
            if (n == -1) {
                throw new IOException("Unexpected EOF while reading bulk");
            }
            offset += n;
        }
        // consume trailing \r\n
        expectCrlf();
        return new String(buf, StandardCharsets.UTF_8);
    }

    private List<Object> readArray() throws IOException {
        long count = Long.parseLong(readLine());
        if (count < 0) {
            return null;
        }
        List<Object> list = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            list.add(readTypedResponse());
        }
        return list;
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int n = in.read();
                if (n != '\n') {
                    throw new IOException("Expected \\n after \\r");
                }
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private void expectCrlf() throws IOException {
        int c1 = in.read();
        int c2 = in.read();
        if (c1 != '\r' || c2 != '\n') {
            throw new IOException("Expected CRLF");
        }
    }

    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            throw new IllegalStateException("Client is not connected. Call connect() first.");
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Exception
    // ──────────────────────────────────────────────────────────────

    public static class RedisException extends RuntimeException {
        public RedisException(String message) {
            super(message);
        }
    }
}