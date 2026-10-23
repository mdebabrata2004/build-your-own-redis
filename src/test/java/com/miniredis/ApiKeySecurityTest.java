package com.miniredis;

import com.miniredis.metadata.Account;
import com.miniredis.metadata.ApiKey;
import com.miniredis.metadata.MetadataRepository;
import com.miniredis.metadata.Project;
import com.miniredis.security.ApiKeyService;
import com.miniredis.security.Permission;
import com.miniredis.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeySecurityTest {

    @TempDir
    Path tempDir;

    private MetadataRepository repository;
    private ApiKeyService apiKeyService;
    private Project project;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("test_meta.db");
        repository = new MetadataRepository(dbPath);
        apiKeyService = new ApiKeyService(repository, "super-secret-test-pepper-12345");

        Account account = new Account("acc_1", "test@domain.com", "Test Owner", Instant.now(), "ACTIVE");
        repository.createAccount(account);

        project = new Project("proj_test", "acc_1", "Test Project", "test-proj", Instant.now(), 50, 100, 10_000_000);
        repository.createProject(project);
    }

    @AfterEach
    void tearDown() {
        repository.close();
    }

    @Test
    @DisplayName("API Key generation produces ld_live_ prefix and stores only HMAC hash")
    void apiKeyGenerationAndHashing() {
        ApiKeyService.GeneratedKey gen = apiKeyService.createKey(project.id(), "Server Key", "read,write", null);

        assertThat(gen.plaintextSecret()).startsWith("ld_live_");
        assertThat(gen.metadata().keyPrefix()).startsWith("ld_live_");

        // Verify the database contains the hash, NOT the plaintext secret
        Optional<ApiKey> storedKey = repository.findApiKeyByHash(gen.metadata().keyHash());
        assertThat(storedKey).isPresent();
        assertThat(storedKey.get().keyHash()).isNotEqualTo(gen.plaintextSecret());
        assertThat(storedKey.get().keyHash()).hasSize(64); // 256-bit hex hash
    }

    @Test
    @DisplayName("Authentication succeeds with valid secret and extracts correct tenant context")
    void authenticationSuccess() {
        ApiKeyService.GeneratedKey gen = apiKeyService.createKey(project.id(), "Prod Key", "read,write", null);

        Optional<SecurityContext> ctx = apiKeyService.authenticate(gen.plaintextSecret());
        assertThat(ctx).isPresent();
        assertThat(ctx.get().projectId()).isEqualTo(project.id());
        assertThat(ctx.get().hasPermission(Permission.READ)).isTrue();
        assertThat(ctx.get().hasPermission(Permission.WRITE)).isTrue();
        assertThat(ctx.get().hasPermission(Permission.ADMIN)).isFalse();
    }

    @Test
    @DisplayName("Authentication fails with invalid secret")
    void authenticationFailure() {
        Optional<SecurityContext> ctx = apiKeyService.authenticate("ld_live_invalid_secret_key");
        assertThat(ctx).isEmpty();
    }

    @Test
    @DisplayName("Revoking an API key immediately terminates access")
    void revocationIsInstant() {
        ApiKeyService.GeneratedKey gen = apiKeyService.createKey(project.id(), "Temp Key", "read", null);

        assertThat(apiKeyService.authenticate(gen.plaintextSecret())).isPresent();

        boolean revoked = apiKeyService.revokeKey(gen.metadata().id(), project.id());
        assertThat(revoked).isTrue();

        // Must fail immediately after revocation
        assertThat(apiKeyService.authenticate(gen.plaintextSecret())).isEmpty();
    }

    @Test
    @DisplayName("Scoped permissions guard sensitive operations")
    void permissionScopes() {
        ApiKeyService.GeneratedKey readOnly = apiKeyService.createKey(project.id(), "Reader", "read", null);
        SecurityContext ctx = apiKeyService.authenticate(readOnly.plaintextSecret()).orElseThrow();

        assertThat(ctx.hasPermission(Permission.READ)).isTrue();
        assertThat(ctx.hasPermission(Permission.WRITE)).isFalse();

        assertThatThrownBy(() -> ctx.checkPermission(Permission.WRITE))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("missing required scope 'write'");
    }
}
