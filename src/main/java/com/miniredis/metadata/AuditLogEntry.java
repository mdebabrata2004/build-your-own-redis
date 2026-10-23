package com.miniredis.metadata;

import java.time.Instant;

public record AuditLogEntry(
        long id,
        String projectId,
        String action,
        String actor,
        String details,
        String ipAddress,
        Instant timestamp
) {}
