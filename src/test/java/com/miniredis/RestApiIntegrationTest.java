package com.miniredis;

import com.fasterxml.jackson.databind.JsonNode;
import com.miniredis.config.PlatformConfig;
import com.miniredis.gateway.JsonUtils;
import com.miniredis.gateway.RestApiGateway;
import com.miniredis.metadata.Account;
import com.miniredis.metadata.MetadataRepository;
import com.miniredis.metadata.Project;
import com.miniredis.metrics.MetricsService;
import com.miniredis.ratelimit.RateLimiterService;
import com.miniredis.security.ApiKeyService;
import com.miniredis.storage.EmbeddedStorageEngine;
import com.miniredis.store.DataStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RestApiIntegrationTest {

    private static final int TEST_PORT = 18081;
    private static RestApiGateway gateway;
    private static MetadataRepository repository;
    private static ApiKeyService apiKeyService;
    private static String masterApiKey;
    private static String readOnlyApiKey;
    private static HttpClient httpClient;

    @BeforeAll
    static void startServer(@TempDir Path tempDir) {
        Path metaDb = tempDir.resolve("test_meta.db");
        Path dataFile = tempDir.resolve("test_data.db");

        repository = new MetadataRepository(metaDb);
        apiKeyService = new ApiKeyService(repository, "test-auth-pepper-xyz");

        Account acc = new Account("acc_test", "owner@test.com", "Test Owner", Instant.now(), "ACTIVE");
        repository.createAccount(acc);

        Project proj = new Project("proj_test", "acc_test", "API Test", "api-test", Instant.now(), 100, 200, 10_000_000);
        repository.createProject(proj);

        masterApiKey = apiKeyService.createKey("proj_test", "Master Key", "admin", null).plaintextSecret();
        readOnlyApiKey = apiKeyService.createKey("proj_test", "Read Key", "read", null).plaintextSecret();

        DataStore rawStore = new DataStore(new DataStore.Config()
                .dataFile(dataFile)
                .saveIntervalSeconds(3600)
                .activeExpiration(false)
                .saveOnClose(false)
        );
        EmbeddedStorageEngine storageEngine = new EmbeddedStorageEngine(rawStore);

        PlatformConfig config = new PlatformConfig.Builder()
                .httpPort(TEST_PORT)
                .bindAddress("127.0.0.1")
                .dataDir(tempDir)
                .build();

        gateway = new RestApiGateway(
                config,
                storageEngine,
                repository,
                apiKeyService,
                new RateLimiterService(),
                new MetricsService()
        );
        gateway.start();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterAll
    static void stopServer() {
        if (gateway != null) gateway.close();
        if (repository != null) repository.close();
    }

    @Test
    @DisplayName("GET /health returns 200 UP")
    void healthCheck() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/health"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = JsonUtils.readTree(resp.body());
        assertThat(json.get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("POST and GET /v1/keys/{key} works with Bearer authentication")
    void setAndGetEndpoint() throws Exception {
        // 1. SET
        HttpRequest setReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/v1/keys/message"))
                .header("Authorization", "Bearer " + masterApiKey)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("Hello from REST API!"))
                .build();

        HttpResponse<String> setResp = httpClient.send(setReq, HttpResponse.BodyHandlers.ofString());
        assertThat(setResp.statusCode()).isEqualTo(200);

        // 2. GET
        HttpRequest getReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/v1/keys/message"))
                .header("Authorization", "Bearer " + masterApiKey)
                .GET()
                .build();

        HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertThat(getResp.statusCode()).isEqualTo(200);

        JsonNode getJson = JsonUtils.readTree(getResp.body());
        assertThat(getJson.get("value").asText()).isEqualTo("Hello from REST API!");
    }

    @Test
    @DisplayName("POST /v1/keys/{key}/incr atomically increments counter")
    void incrEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/v1/keys/api_counter/incr?delta=5"))
                .header("Authorization", "Bearer " + masterApiKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = JsonUtils.readTree(resp.body());
        assertThat(json.get("value").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("Request with missing API key returns 401 UNAUTHORIZED")
    void missingAuthReturns401() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/v1/keys/secret"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);

        JsonNode json = JsonUtils.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("Read-only API key cannot perform write operations (returns 403 FORBIDDEN)")
    void readOnlyKeyCannotWrite() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/v1/keys/blockedKey"))
                .header("Authorization", "Bearer " + readOnlyApiKey)
                .POST(HttpRequest.BodyPublishers.ofString("attempted-write"))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(403);

        JsonNode json = JsonUtils.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("POST /v1/pipeline executes multiple batch commands in a single round-trip")
    void pipelineEndpoint() throws Exception {
        String payload = """
            [
                {"cmd": "SET", "args": ["piped1", "alpha"]},
                {"cmd": "SET", "args": ["piped2", "beta"]},
                {"cmd": "GET", "args": ["piped1"]},
                {"cmd": "GET", "args": ["piped2"]}
            ]
        """;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/v1/pipeline"))
                .header("Authorization", "Bearer " + masterApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = JsonUtils.readTree(resp.body());
        assertThat(json.get("results").get(2).asText()).isEqualTo("alpha");
        assertThat(json.get("results").get(3).asText()).isEqualTo("beta");
    }

    @Test
    @DisplayName("GET and POST /api/projects manages projects without serialization errors")
    void projectsManagementEndpoint() throws Exception {
        // 1. GET /api/projects
        HttpRequest listReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/api/projects"))
                .GET()
                .build();

        HttpResponse<String> listResp = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
        assertThat(listResp.statusCode()).isEqualTo(200);

        JsonNode listJson = JsonUtils.readTree(listResp.body());
        assertThat(listJson.get("success").asBoolean()).isTrue();
        assertThat(listJson.get("projects").isArray()).isTrue();

        // 2. POST /api/projects
        String newProj = """
            {
                "name": "Integration Project",
                "slug": "integration-proj",
                "rateLimitRps": 120,
                "rateLimitBurst": 240
            }
        """;
        HttpRequest createReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/api/projects"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(newProj))
                .build();

        HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
        assertThat(createResp.statusCode()).isEqualTo(201);

        JsonNode createJson = JsonUtils.readTree(createResp.body());
        assertThat(createJson.get("success").asBoolean()).isTrue();
        assertThat(createJson.get("project").get("name").asText()).isEqualTo("Integration Project");
    }
}
