package com.miniredis;

import com.miniredis.server.RedisServer;
import com.miniredis.store.DataStore;

import java.nio.file.Path;

/**
 * Entry point for Mini Redis server.
 *
 * Usage:
 *   java com.miniredis.Main
 *   java com.miniredis.Main 6379
 *   java com.miniredis.Main 6379 64
 *   java com.miniredis.Main 6379 64 127.0.0.1
 */
public final class Main {

    private Main() {
        // Prevent instantiation
    }

    public static void main(String[] args) {
        int port = 6379;
        int threads = 32;
        String bind = "0.0.0.0";
        int maxClients = 1000;

        // Args: [port] [threads] [bind]
        if (args.length >= 1) {
            port = parseInt(args[0], "port");
        }
        if (args.length >= 2) {
            threads = parseInt(args[1], "threads");
        }
        if (args.length >= 3) {
            bind = args[2];
        }

        Path dataFile = Path.of("redis.data");

        RedisServer server = new RedisServer(
                new RedisServer.Config()
                        .port(port)
                        .bind(bind)
                        .threadPoolSize(threads)
                        .maxClients(maxClients)
                        .backlog(256)
                        .tcpKeepAlive(true)
                        .soTimeoutMs(0)
                        .storeConfig(new DataStore.Config()
                                .dataFile(dataFile)
                                .saveIntervalSeconds(5)
                                .maxKeys(0)              // 0 = unlimited
                                .activeExpiration(true)
                                .expirationSampleSize(20)
                                .saveOnClose(true)
                        )
        );

        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║          Mini Redis Server           ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("  Bind       : " + bind + ":" + port);
        System.out.println("  Threads    : " + threads);
        System.out.println("  Max clients: " + maxClients);
        System.out.println("  Data file  : " + dataFile.toAbsolutePath());
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();

        // Blocks until shutdown
        server.start();
    }

    private static int parseInt(String raw, String name) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            System.err.println("Invalid " + name + ": " + raw);
            System.exit(1);
            return 0; // unreachable
        }
    }
}