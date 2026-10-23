package com.miniredis.security;

import java.util.Set;

public record SecurityContext(
        String projectId,
        String accountId,
        String apiKeyId,
        String keyName,
        Set<String> scopes,
        int rateLimitRps,
        int rateLimitBurst,
        long maxStorageBytes
) {
    public boolean hasPermission(Permission permission) {
        if (scopes.contains("admin")) {
            return true;
        }
        return scopes.contains(permission.name().toLowerCase());
    }

    public void checkPermission(Permission permission) {
        if (!hasPermission(permission)) {
            throw new SecurityException("Forbidden: missing required scope '" + permission.name().toLowerCase() + "'");
        }
    }
}
