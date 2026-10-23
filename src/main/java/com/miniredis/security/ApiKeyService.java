package com.miniredis.security;

import com.miniredis.metadata.ApiKey;
import com.miniredis.metadata.MetadataRepository;
import com.miniredis.metadata.Project;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for generating, hashing, verifying, and revoking API keys.
 * Raw API secrets are NEVER stored; only HMAC-SHA256 hashes are persisted.
 */
public final class ApiKeyService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MetadataRepository repository;
    private final byte[] pepperBytes;

    public record GeneratedKey(ApiKey metadata, String plaintextSecret) {}

    public ApiKeyService(MetadataRepository repository, String authPepper) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        Objects.requireNonNull(authPepper, "authPepper cannot be null");
        this.pepperBytes = authPepper.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates a new API key for a project. The returned plaintext secret is shown only once.
     */
    public GeneratedKey createKey(String projectId, String name, String scopes, Instant expiresAt) {
        Project project = repository.getProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        String randomHex = HexFormat.of().formatHex(randomBytes);
        String plaintextSecret = "ld_live_" + randomHex;
        String prefix = plaintextSecret.substring(0, 12) + "...";
        String hash = hashSecret(plaintextSecret);

        String keyId = "key_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String cleanScopes = (scopes == null || scopes.isBlank()) ? "read,write" : scopes.trim().toLowerCase();

        ApiKey key = new ApiKey(
                keyId,
                project.id(),
                name,
                prefix,
                hash,
                cleanScopes,
                Instant.now(),
                expiresAt,
                null,
                null
        );

        repository.createApiKey(key);
        return new GeneratedKey(key, plaintextSecret);
    }

    /**
     * Authenticates a plaintext secret and returns the associated SecurityContext.
     */
    public Optional<SecurityContext> authenticate(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank()) {
            return Optional.empty();
        }

        String secret = rawSecret.trim();
        // Remove Bearer if present
        if (secret.startsWith("Bearer ")) {
            secret = secret.substring(7).trim();
        }

        String hash = hashSecret(secret);
        Optional<ApiKey> maybeKey = repository.findApiKeyByHash(hash);
        if (maybeKey.isEmpty()) {
            return Optional.empty();
        }

        ApiKey key = maybeKey.get();
        Instant now = Instant.now();
        if (!key.isValid(now)) {
            return Optional.empty();
        }

        // Fetch project metadata
        Optional<Project> maybeProject = repository.getProject(key.projectId());
        if (maybeProject.isEmpty()) {
            return Optional.empty();
        }
        Project project = maybeProject.get();

        // Update last used timestamp asynchronously/quietly
        repository.updateApiKeyLastUsed(key.id());

        return Optional.of(new SecurityContext(
                project.id(),
                project.accountId(),
                key.id(),
                key.name(),
                key.getScopes(),
                project.rateLimitRps(),
                project.rateLimitBurst(),
                project.maxStorageBytes()
        ));
    }

    public boolean revokeKey(String keyId, String projectId) {
        return repository.revokeApiKey(keyId, projectId);
    }

    public String hashSecret(String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepperBytes, HMAC_ALGORITHM));
            byte[] hmacBytes = mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
