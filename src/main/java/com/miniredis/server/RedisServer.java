package com.miniredis.server;

import com.miniredis.command.CommandHandler;
import com.miniredis.store.DataStore;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hardened Mini Redis TCP server.
 *
 * - maxClients enforcement
 * - configurable bind address
 * - TCP keepalive
 * - reject when saturated
 * - graceful shutdown
 */
public final class RedisServer implements AutoCloseable {

    // ──────────────────────────────────────────────────────────────
    // Config
    // ──────────────────────────────────────────────────────────────

    public static final class Config {
        private int port = 6379;
        private String bind = "0.0.0.0";
        private int threadPoolSize = 32;
        private int backlog = 128;
        private int maxClients = 1000;       // 0 = unlimited
        private int soTimeoutMs = 0;         // 0 = infinite
        private boolean tcpKeepAlive = true;
        private DataStore.Config storeConfig = new DataStore.Config();

        public Config port(int port) {
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
            this.port = port;
            return this;
        }

        public Config bind(String host) {
            this.bind = Objects.requireNonNull(host);
            return this;
        }

        public Config threadPoolSize(int size) {
            if (size < 1) throw new IllegalArgumentException("threadPoolSize must be > 0");
            this.threadPoolSize = size;
            return this;
        }

        public Config backlog(int backlog) {
            if (backlog < 1) throw new IllegalArgumentException("backlog must be > 0");
            this.backlog = backlog;
            return this;
        }

        public Config maxClients(int max) {
            if (max < 0) throw new IllegalArgumentException("maxClients must be >= 0");
            this.maxClients = max;
            return this;
        }

        public Config soTimeoutMs(int ms) {
            if (ms < 0) throw new IllegalArgumentException("soTimeoutMs must be >= 0");
            this.soTimeoutMs = ms;
            return this;
        }

        public Config tcpKeepAlive(boolean enabled) {
            this.tcpKeepAlive = enabled;
            return this;
        }

        public Config storeConfig(DataStore.Config storeConfig) {
            this.storeConfig = Objects.requireNonNull(storeConfig);
            return this;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────

    private final Config config;
    private final DataStore store;
    private final CommandHandler handler;
    private final ExecutorService executor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong rejectedConnections = new AtomicLong(0);

    private volatile ServerSocket serverSocket;

    // ──────────────────────────────────────────────────────────────
    // Constructors
    // ──────────────────────────────────────────────────────────────

    public RedisServer(int port) {
        this(new Config().port(port));
    }

    public RedisServer(int port, int threadPoolSize) {
        this(new Config().port(port).threadPoolSize(threadPoolSize));
    }

    public RedisServer(Config config) {
        this.config = Objects.requireNonNull(config);
        this.store = new DataStore(config.storeConfig);
        this.handler = new CommandHandler(store);

        this.executor = Executors.newFixedThreadPool(
                config.threadPoolSize,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("miniredis-worker-" + t.threadId());
                    t.setDaemon(true);
                    return t;
                }
        );
    }

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already running");
        }

        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(this::shutdown, "miniredis-shutdown")
            );
        }

        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.setPerformancePreferences(0, 1, 2); // latency over bandwidth

            InetAddress addr = InetAddress.getByName(config.bind);
            serverSocket.bind(new InetSocketAddress(addr, config.port), config.backlog);

            log("Mini Redis started");
            log("  bind     : " + config.bind + ":" + config.port);
            log("  threads  : " + config.threadPoolSize);
            log("  maxClients: " + (config.maxClients == 0 ? "unlimited" : config.maxClients));
            log("  backlog  : " + config.backlog);

            acceptLoop();

        } catch (IOException e) {
            running.set(false);
            log("Failed to start: " + e.getMessage());
            throw new IllegalStateException("Could not bind to " + config.bind + ":" + config.port, e);
        } finally {
            shutdown();
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                onAccept(client);
            } catch (SocketException e) {
                if (running.get()) {
                    log("Accept error: " + e.getMessage());
                }
                // else: expected during shutdown
            } catch (IOException e) {
                if (running.get()) {
                    log("I/O accept error: " + e.getMessage());
                }
            }
        }
    }

    private void onAccept(Socket client) {
        try {
            // Enforce max clients
            if (config.maxClients > 0 && activeConnections.get() >= config.maxClients) {
                rejectedConnections.incrementAndGet();
                log("Rejected connection (maxClients=" + config.maxClients + "): "
                        + client.getRemoteSocketAddress());
                closeQuietly(client);
                return;
            }

            if (config.soTimeoutMs > 0) {
                client.setSoTimeout(config.soTimeoutMs);
            }
            if (config.tcpKeepAlive) {
                client.setKeepAlive(true);
            }
            client.setTcpNoDelay(true);

            totalConnections.incrementAndGet();
            activeConnections.incrementAndGet();

            log("Client connected: " + client.getRemoteSocketAddress()
                    + " (active=" + activeConnections.get() + ")");

            try {
                executor.submit(() -> {
                    try {
                        new ClientHandler(client, handler).run();
                    } finally {
                        activeConnections.decrementAndGet();
                        closeQuietly(client);
                    }
                });
            } catch (RejectedExecutionException rex) {
                // Pool saturated
                activeConnections.decrementAndGet();
                rejectedConnections.incrementAndGet();
                log("Rejected execution (pool full): " + client.getRemoteSocketAddress());
                closeQuietly(client);
            }

        } catch (IOException e) {
            activeConnections.decrementAndGet();
            closeQuietly(client);
            log("Failed to configure client socket: " + e.getMessage());
        }
    }

    /**
     * Graceful shutdown: stop accept → drain workers → close store.
     */
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        log("Shutting down...");

        // 1. Stop accepting
        closeQuietly(serverSocket);
        serverSocket = null;

        // 2. Drain workers
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                log("Workers did not finish in time — forcing shutdown");
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 3. Persist + close store
        try {
            store.close();
        } catch (Exception e) {
            log("Error closing DataStore: " + e.getMessage());
        }

        log("Stopped. total=" + totalConnections.get()
                + " rejected=" + rejectedConnections.get());
    }

    @Override
    public void close() {
        shutdown();
    }

    // ──────────────────────────────────────────────────────────────
    // Metrics / accessors
    // ──────────────────────────────────────────────────────────────

    public long getTotalConnections() {
        return totalConnections.get();
    }

    public long getActiveConnections() {
        return activeConnections.get();
    }

    public long getRejectedConnections() {
        return rejectedConnections.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    public DataStore getStore() {
        return store;
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    private static void log(String msg) {
        System.out.println("[miniredis] " + msg);
    }
}