package com.miniredis.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.miniredis.metadata.ApiKey;
import com.miniredis.metadata.AuditLogEntry;
import com.miniredis.metadata.MetadataRepository;
import com.miniredis.metadata.Project;
import com.miniredis.metrics.MetricsService;
import com.miniredis.security.ApiKeyService;
import com.miniredis.storage.StorageEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST Management API for managing accounts, projects, databases,
 * scoped API keys, audit logs, and operational snapshots.
 */
public final class ManagementHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ManagementHandler.class);

    private final MetadataRepository repository;
    private final ApiKeyService apiKeyService;
    private final StorageEngine rootEngine;
    private final MetricsService metricsService;

    public ManagementHandler(
            MetadataRepository repository,
            ApiKeyService apiKeyService,
            StorageEngine rootEngine,
            MetricsService metricsService
    ) {
        this.repository = repository;
        this.apiKeyService = apiKeyService;
        this.rootEngine = rootEngine;
        this.metricsService = metricsService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();

        if (method.equals("OPTIONS")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            if (path.equals("/api/projects") && method.equals("GET")) {
                handleListProjects(exchange);
            } else if (path.equals("/api/projects") && method.equals("POST")) {
                handleCreateProject(exchange);
            } else if (path.matches("^/api/projects/[^/]+$") && method.equals("GET")) {
                handleGetProject(exchange, path);
            } else if (path.matches("^/api/projects/[^/]+$") && method.equals("DELETE")) {
                handleDeleteProject(exchange, path);
            } else if (path.matches("^/api/projects/[^/]+/keys$") && method.equals("GET")) {
                handleListKeys(exchange, path);
            } else if (path.matches("^/api/projects/[^/]+/keys$") && method.equals("POST")) {
                handleCreateKey(exchange, path);
            } else if (path.matches("^/api/projects/[^/]+/keys/[^/]+$") && method.equals("DELETE")) {
                handleRevokeKey(exchange, path);
            } else if (path.equals("/api/metrics") && method.equals("GET")) {
                handleGetMetrics(exchange);
            } else if (path.equals("/api/audit-logs") && method.equals("GET")) {
                handleGetAuditLogs(exchange);
            } else if (path.equals("/api/backups/snapshot") && method.equals("POST")) {
                handleTriggerSnapshot(exchange);
            } else {
                HttpExchangeUtils.sendError(exchange, 404, "NOT_FOUND", "Management endpoint not found: " + path);
            }
        } catch (IllegalArgumentException iae) {
            HttpExchangeUtils.sendError(exchange, 400, "BAD_REQUEST", iae.getMessage());
        } catch (Exception e) {
            log.error("Management error on {}: {}", path, e.getMessage(), e);
            HttpExchangeUtils.sendError(exchange, 500, "INTERNAL_ERROR", "Internal error: " + e.getMessage());
        }
    }

    private void handleListProjects(HttpExchange exchange) throws IOException {
        List<Project> list = repository.listProjects();
        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "projects", list));
    }

    private void handleCreateProject(HttpExchange exchange) throws IOException {
        String body = HttpExchangeUtils.readBodyString(exchange, 32768);
        JsonNode json = JsonUtils.readTree(body);

        String name = json.has("name") ? json.get("name").asText().trim() : null;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project 'name' is required");
        }

        String baseSlug = json.has("slug") && !json.get("slug").asText().isBlank()
                ? json.get("slug").asText().trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-")
                : name.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        if (baseSlug.isBlank()) {
            baseSlug = "project";
        }

        String slug = baseSlug;
        int counter = 1;
        while (repository.getProjectBySlug(slug).isPresent()) {
            slug = baseSlug + "-" + counter;
            counter++;
        }

        String projectId = "proj_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int rps = json.has("rateLimitRps") ? json.get("rateLimitRps").asInt() : 100;
        int burst = json.has("rateLimitBurst") ? json.get("rateLimitBurst").asInt() : 200;
        long maxStorage = json.has("maxStorageBytes") ? json.get("maxStorageBytes").asLong() : 100L * 1024 * 1024;

        String accountId = json.has("accountId") && !json.get("accountId").asText().isBlank()
                ? json.get("accountId").asText().trim()
                : "acc_default";
        if (repository.getAccount(accountId).isEmpty()) {
            accountId = "acc_default";
        }

        Project p = new Project(projectId, accountId, name, slug, Instant.now(), rps, burst, maxStorage);
        repository.createProject(p);

        repository.recordAuditLog(new AuditLogEntry(
                0,
                projectId,
                "PROJECT_CREATED",
                "admin",
                "Created project: " + name + " (" + slug + ")",
                exchange.getRemoteAddress().toString(),
                Instant.now()
        ));

        HttpExchangeUtils.sendJson(exchange, 201, Map.of("success", true, "project", p));
    }

    private void handleGetProject(HttpExchange exchange, String path) throws IOException {
        String projectId = path.substring("/api/projects/".length());
        Optional<Project> p = repository.getProject(projectId);
        if (p.isEmpty()) {
            HttpExchangeUtils.sendError(exchange, 404, "NOT_FOUND", "Project not found: " + projectId);
        } else {
            HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "project", p.get()));
        }
    }

    private void handleDeleteProject(HttpExchange exchange, String path) throws IOException {
        String projectId = path.substring("/api/projects/".length());
        boolean deleted = repository.deleteProject(projectId);

        if (deleted) {
            repository.recordAuditLog(new AuditLogEntry(
                    0,
                    projectId,
                    "PROJECT_DELETED",
                    "admin",
                    "Deleted project: " + projectId,
                    exchange.getRemoteAddress().toString(),
                    Instant.now()
            ));
        }

        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", deleted, "projectId", projectId));
    }

    private void handleListKeys(HttpExchange exchange, String path) throws IOException {
        // path: /api/projects/{id}/keys
        String[] parts = path.split("/");
        String projectId = parts[3];

        List<ApiKey> keys = repository.listApiKeys(projectId);
        // Map to safe DTO (omitting hash)
        List<Map<String, Object>> safeList = keys.stream().map(k -> Map.<String, Object>of(
                "id", k.id(),
                "projectId", k.projectId(),
                "name", k.name(),
                "keyPrefix", k.keyPrefix(),
                "scopes", k.scopesRaw(),
                "createdAt", k.createdAt().toString(),
                "isRevoked", k.isRevoked(),
                "isExpired", k.isExpired(Instant.now()),
                "lastUsedAt", k.lastUsedAt() != null ? k.lastUsedAt().toString() : "never"
        )).toList();

        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "keys", safeList));
    }

    private void handleCreateKey(HttpExchange exchange, String path) throws IOException {
        String[] parts = path.split("/");
        String projectId = parts[3];

        String body = HttpExchangeUtils.readBodyString(exchange, 32768);
        JsonNode json = JsonUtils.readTree(body);

        String name = json.has("name") ? json.get("name").asText().trim() : "Default API Key";
        String scopes = json.has("scopes") ? json.get("scopes").asText().trim() : "read,write";
        Instant expiresAt = null;
        if (json.has("expiresAt")) {
            expiresAt = Instant.parse(json.get("expiresAt").asText());
        }

        ApiKeyService.GeneratedKey generated = apiKeyService.createKey(projectId, name, scopes, expiresAt);

        repository.recordAuditLog(new AuditLogEntry(
                0,
                projectId,
                "API_KEY_CREATED",
                "admin",
                "Created API key '" + name + "' with scopes [" + scopes + "]",
                exchange.getRemoteAddress().toString(),
                Instant.now()
        ));

        HttpExchangeUtils.sendJson(exchange, 201, Map.of(
                "success", true,
                "apiKey", Map.of(
                        "id", generated.metadata().id(),
                        "projectId", generated.metadata().projectId(),
                        "name", generated.metadata().name(),
                        "keyPrefix", generated.metadata().keyPrefix(),
                        "scopes", generated.metadata().scopesRaw(),
                        "createdAt", generated.metadata().createdAt().toString()
                ),
                "plaintextSecret", generated.plaintextSecret(),
                "notice", "This plaintext secret is displayed ONLY ONCE. Save it securely."
        ));
    }

    private void handleRevokeKey(HttpExchange exchange, String path) throws IOException {
        // path: /api/projects/{projectId}/keys/{keyId}
        String[] parts = path.split("/");
        String projectId = parts[3];
        String keyId = parts[5];

        boolean ok = apiKeyService.revokeKey(keyId, projectId);

        if (ok) {
            repository.recordAuditLog(new AuditLogEntry(
                    0,
                    projectId,
                    "API_KEY_REVOKED",
                    "admin",
                    "Revoked API key: " + keyId,
                    exchange.getRemoteAddress().toString(),
                    Instant.now()
            ));
        }

        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", ok, "keyId", keyId));
    }

    private void handleGetMetrics(HttpExchange exchange) throws IOException {
        long totalKeys = rootEngine.dbSize();
        Map<String, Object> snapshot = metricsService.getMetricsSnapshot(totalKeys);
        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "metrics", snapshot));
    }

    private void handleGetAuditLogs(HttpExchange exchange) throws IOException {
        Map<String, String> query = HttpExchangeUtils.parseQueryParams(exchange.getRequestURI());
        String projectId = query.get("projectId");
        int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 50;

        List<AuditLogEntry> logs = repository.listAuditLogs(projectId, limit);
        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "logs", logs));
    }

    private void handleTriggerSnapshot(HttpExchange exchange) throws IOException {
        rootEngine.save();
        HttpExchangeUtils.sendJson(exchange, 200, Map.of(
                "success", true,
                "message", "RDB snapshot saved successfully with CRC32 checksum"
        ));
    }
}
