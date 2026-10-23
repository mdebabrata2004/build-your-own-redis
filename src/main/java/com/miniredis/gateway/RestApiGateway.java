package com.miniredis.gateway;

import com.miniredis.config.PlatformConfig;
import com.miniredis.dashboard.DashboardHandler;
import com.miniredis.metadata.MetadataRepository;
import com.miniredis.metrics.MetricsService;
import com.miniredis.ratelimit.RateLimiterService;
import com.miniredis.security.ApiKeyService;
import com.miniredis.storage.StorageEngine;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production-ready HTTP Gateway powered by Java Virtual Threads.
 * Exposes the REST API, Developer Dashboard, and Prometheus-compatible metrics.
 */
public final class RestApiGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RestApiGateway.class);

    private final PlatformConfig config;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RestApiGateway(
            PlatformConfig config,
            StorageEngine rootEngine,
            MetadataRepository repository,
            ApiKeyService apiKeyService,
            RateLimiterService rateLimiterService,
            MetricsService metricsService
    ) {
        this.config = config;
        try {
            this.server = HttpServer.create(new InetSocketAddress(config.getBindAddress(), config.getHttpPort()), 512);
            // High-throughput Java Virtual Thread executor
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);

            // Register handlers
            this.server.createContext("/health", new HealthHandler());
            this.server.createContext("/v1/", new DataHandler(
                    rootEngine,
                    apiKeyService,
                    rateLimiterService,
                    metricsService,
                    config.getMaxPayloadSizeBytes()
            ));
            this.server.createContext("/api/", new ManagementHandler(
                    repository,
                    apiKeyService,
                    rootEngine,
                    metricsService
            ));
            this.server.createContext("/dashboard", new DashboardHandler());
            this.server.createContext("/favicon.ico", exchange -> {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });

        } catch (IOException e) {
            throw new RuntimeException("Failed to bind REST API Gateway on " + config.getBindAddress() + ":" + config.getHttpPort(), e);
        }
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            server.start();
            log.info("REST API Gateway started on http://{}:{}", config.getBindAddress(), config.getHttpPort());
            log.info("Developer Dashboard available at http://localhost:{}/dashboard", config.getHttpPort());
        }
    }

    @Override
    public synchronized void close() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping REST API Gateway...");
            server.stop(1); // 1 second grace period
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("REST API Gateway stopped.");
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
