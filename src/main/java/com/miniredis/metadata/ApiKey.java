package com.miniredis.metadata;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public record ApiKey(
        String id,
        String projectId,
        String name,
        String keyPrefix,
        String keyHash,
        String scopesRaw,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant lastUsedAt
) {
    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public boolean isValid(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    public Set<String> getScopes() {
        if (scopesRaw == null || scopesRaw.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(scopesRaw.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean hasScope(String scope) {
        Set<String> scopes = getScopes();
        return scopes.contains("admin") || scopes.contains(scope.toLowerCase());
    }
}
