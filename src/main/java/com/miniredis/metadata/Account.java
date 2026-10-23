package com.miniredis.metadata;

import java.time.Instant;

public record Account(
        String id,
        String email,
        String name,
        Instant createdAt,
        String status
) {}
