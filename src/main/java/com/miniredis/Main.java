package com.miniredis;

import com.miniredis.config.PlatformConfig;
import com.miniredis.gateway.RestApiGateway;
import com.miniredis.metadata.Account;
import com.miniredis.metadata.MetadataRepository;
import com.miniredis.metadata.Project;
import com.miniredis.metrics.MetricsService;
import com.miniredis.ratelimit.RateLimiterService;
import com.miniredis.security.ApiKeyService;
import com.miniredis.server.RedisServer;
import com.miniredis.storage.EmbeddedStorageEngine;
import com.miniredis.store.DataStore;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

/**
 * Main launcher for the LocalData Production Redis-Compatible Platform.
 * Boots the Storage Engine, SQLite Control Plane, Virtual-Thread REST API Gateway,
 * Developer Dashboard, and RESP2 TCP Server.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        PlatformConfig config = PlatformConfig.fromEnv();

        try {
            Files.createDirectories(config.getDataDir());
        } catch (Exception e) {
            System.err.println("Could not create data directory: " + e.getMessage());
        }

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          LocalData Managed Redis Platform v1.0.0             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println("  Data Directory   : " + config.getDataDir().toAbsolutePath());
        System.out.println("  REST & Dashboard : http://" + config.getBindAddress() + ":" + config.getHttpPort());
        System.out.println("  Redis RESP TCP   : " + config.getBindAddress() + ":" + config.getTcpPort());
        System.out.println("  Storage Engine   : Embedded In-Memory (CRC32 Snapshotting)");
        System.out.println();

        // 1. Control Plane & Security
        MetadataRepository repository = new MetadataRepository(config.getMetaDbPath());
        ApiKeyService apiKeyService = new ApiKeyService(repository, config.getAuthPepper());

        // Bootstrap default account, project and API key if none exist
        String bootstrapKey = bootstrapDefaultsIfEmpty(repository, apiKeyService);

        // 2. Storage Engine
        DataStore rawStore = new DataStore(new DataStore.Config()
                .dataFile(config.getDataStorePath())
                .saveIntervalSeconds(5)
                .activeExpiration(true)
                .saveOnClose(true)
        );
        EmbeddedStorageEngine storageEngine = new EmbeddedStorageEngine(rawStore);

        // 3. Rate Limiter & Metrics
        RateLimiterService rateLimiterService = new RateLimiterService();
        MetricsService metricsService = new MetricsService();

        // 4. REST API Gateway & Developer Dashboard
        RestApiGateway restGateway = new RestApiGateway(
                config,
                storageEngine,
                repository,
                apiKeyService,
                rateLimiterService,
                metricsService
        );
        restGateway.start();

        // 5. Redis Protocol TCP Server
        RedisServer.Config serverConfig = new RedisServer.Config()
                .port(config.getTcpPort())
                .bind(config.getBindAddress())
                .threadPoolSize(32)
                .maxClients(1000)
                .tcpKeepAlive(true);

        RedisServer redisServer = new RedisServer(
                serverConfig,
                storageEngine,
                apiKeyService,
                config.isRequireAuthForTcp()
        );

        if (bootstrapKey != null) {
            System.out.println("🔑 BOOTSTRAP API KEY (Save this now!):");
            System.out.println("   " + bootstrapKey);
            System.out.println();
            System.out.println("👉 Open Dashboard: http://localhost:" + config.getHttpPort() + "/dashboard");
            System.out.println();
        }

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nGraceful shutdown initiated...");
            redisServer.shutdown();
            restGateway.close();
            repository.close();
            System.out.println("LocalData platform stopped successfully.");
        }));

        // Blocks on TCP server accept loop
        redisServer.start();
    }

    private static String bootstrapDefaultsIfEmpty(MetadataRepository repository, ApiKeyService apiKeyService) {
        List<Project> projects = repository.listProjects();
        if (projects.isEmpty()) {
            Account defaultAccount = new Account(
                    "acc_default",
                    "admin@localdata.dev",
                    "Default Admin",
                    Instant.now(),
                    "ACTIVE"
            );
            repository.createAccount(defaultAccount);

            Project defaultProject = new Project(
                    "proj_default",
                    "acc_default",
                    "Default Project",
                    "default",
                    Instant.now(),
                    100,
                    200,
                    100L * 1024 * 1024
            );
            repository.createProject(defaultProject);

            ApiKeyService.GeneratedKey key = apiKeyService.createKey(
                    "proj_default",
                    "Master Admin Key",
                    "admin",
                    null
            );
            return key.plaintextSecret();
        }
        return null;
    }
}
