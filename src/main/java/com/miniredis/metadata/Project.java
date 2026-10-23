package com.miniredis.metadata;

import java.time.Instant;

public record Project(
        String id,
        String accountId,
        String name,
        String slug,
        Instant createdAt,
        int rateLimitRps,
        int rateLimitBurst,
        long maxStorageBytes
) {}
