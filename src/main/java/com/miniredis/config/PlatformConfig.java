package com.miniredis.config;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Global configuration for the managed Redis-compatible data platform.
 * Supports environment variables with safe production defaults.
 */
public final class PlatformConfig {

    private final int tcpPort;
    private final int httpPort;
    private final String bindAddress;
    private final Path dataDir;
    private final Path metaDbPath;
    private final Path dataStorePath;
    private final String authPepper;
    private final int defaultRateLimitRps;
    private final int defaultRateLimitBurst;
    private final int maxPayloadSizeBytes;
    private final boolean requireAuthForTcp;

    private PlatformConfig(Builder b) {
        this.tcpPort = b.tcpPort;
        this.httpPort = b.httpPort;
        this.bindAddress = b.bindAddress;
        this.dataDir = b.dataDir;
        this.metaDbPath = b.dataDir.resolve("platform_meta.db");
        this.dataStorePath = b.dataDir.resolve("redis.data");
        this.authPepper = b.authPepper;
        this.defaultRateLimitRps = b.defaultRateLimitRps;
        this.defaultRateLimitBurst = b.defaultRateLimitBurst;
        this.maxPayloadSizeBytes = b.maxPayloadSizeBytes;
        this.requireAuthForTcp = b.requireAuthForTcp;
    }

    public static PlatformConfig fromEnv() {
        Builder b = new Builder();
        String pTcp = System.getenv("REDIS_PORT");
        if (pTcp != null && !pTcp.isBlank()) b.tcpPort(Integer.parseInt(pTcp.trim()));

        String pHttp = System.getenv("HTTP_PORT");
        if (pHttp == null || pHttp.isBlank()) pHttp = System.getenv("PORT");
        if (pHttp != null && !pHttp.isBlank()) b.httpPort(Integer.parseInt(pHttp.trim()));

        String bind = System.getenv("BIND_ADDRESS");
        if (bind != null && !bind.isBlank()) b.bindAddress(bind.trim());

        String dir = System.getenv("DATA_DIR");
        if (dir != null && !dir.isBlank()) b.dataDir(Path.of(dir.trim()));

        String pepper = System.getenv("AUTH_PEPPER");
        if (pepper != null && !pepper.isBlank()) b.authPepper(pepper.trim());

        String rps = System.getenv("DEFAULT_RATE_LIMIT_RPS");
        if (rps != null && !rps.isBlank()) b.defaultRateLimitRps(Integer.parseInt(rps.trim()));

        String burst = System.getenv("DEFAULT_RATE_LIMIT_BURST");
        if (burst != null && !burst.isBlank()) b.defaultRateLimitBurst(Integer.parseInt(burst.trim()));

        String maxBytes = System.getenv("MAX_PAYLOAD_SIZE_BYTES");
        if (maxBytes != null && !maxBytes.isBlank()) b.maxPayloadSizeBytes(Integer.parseInt(maxBytes.trim()));

        String requireTcpAuth = System.getenv("REQUIRE_TCP_AUTH");
        if (requireTcpAuth != null && !requireTcpAuth.isBlank()) {
            b.requireAuthForTcp(Boolean.parseBoolean(requireTcpAuth.trim()));
        }

        return b.build();
    }

    public int getTcpPort() { return tcpPort; }
    public int getHttpPort() { return httpPort; }
    public String getBindAddress() { return bindAddress; }
    public Path getDataDir() { return dataDir; }
    public Path getMetaDbPath() { return metaDbPath; }
    public Path getDataStorePath() { return dataStorePath; }
    public String getAuthPepper() { return authPepper; }
    public int getDefaultRateLimitRps() { return defaultRateLimitRps; }
    public int getDefaultRateLimitBurst() { return defaultRateLimitBurst; }
    public int getMaxPayloadSizeBytes() { return maxPayloadSizeBytes; }
    public boolean isRequireAuthForTcp() { return requireAuthForTcp; }

    public static final class Builder {
        private int tcpPort = 6379;
        private int httpPort = 8080;
        private String bindAddress = "0.0.0.0";
        private Path dataDir = Path.of("data");
        private String authPepper = generateDefaultPepper();
        private int defaultRateLimitRps = 100;
        private int defaultRateLimitBurst = 200;
        private int maxPayloadSizeBytes = 512 * 1024; // 512 KB
        private boolean requireAuthForTcp = true;

        public Builder tcpPort(int port) { this.tcpPort = port; return this; }
        public Builder httpPort(int port) { this.httpPort = port; return this; }
        public Builder bindAddress(String addr) { this.bindAddress = Objects.requireNonNull(addr); return this; }
        public Builder dataDir(Path dir) { this.dataDir = Objects.requireNonNull(dir); return this; }
        public Builder authPepper(String pepper) { this.authPepper = Objects.requireNonNull(pepper); return this; }
        public Builder defaultRateLimitRps(int rps) { this.defaultRateLimitRps = rps; return this; }
        public Builder defaultRateLimitBurst(int burst) { this.defaultRateLimitBurst = burst; return this; }
        public Builder maxPayloadSizeBytes(int bytes) { this.maxPayloadSizeBytes = bytes; return this; }
        public Builder requireAuthForTcp(boolean require) { this.requireAuthForTcp = require; return this; }

        public PlatformConfig build() {
            return new PlatformConfig(this);
        }

        private static String generateDefaultPepper() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        }
    }
}
