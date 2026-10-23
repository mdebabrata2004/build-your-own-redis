package com.miniredis.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.miniredis.metrics.MetricsService;
import com.miniredis.ratelimit.RateLimiterService;
import com.miniredis.security.ApiKeyService;
import com.miniredis.security.Permission;
import com.miniredis.security.SecurityContext;
import com.miniredis.storage.ScanResult;
import com.miniredis.storage.SetOptions;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.TenantStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles all /v1/ data operations under strict multi-tenant isolation,
 * API-key authorization, scoped permission checking, and rate limiting.
 */
public final class DataHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(DataHandler.class);

    private final StorageEngine rootEngine;
    private final ApiKeyService apiKeyService;
    private final RateLimiterService rateLimiterService;
    private final MetricsService metricsService;
    private final int maxPayloadSizeBytes;

    public DataHandler(
            StorageEngine rootEngine,
            ApiKeyService apiKeyService,
            RateLimiterService rateLimiterService,
            MetricsService metricsService,
            int maxPayloadSizeBytes
    ) {
        this.rootEngine = rootEngine;
        this.apiKeyService = apiKeyService;
        this.rateLimiterService = rateLimiterService;
        this.metricsService = metricsService;
        this.maxPayloadSizeBytes = maxPayloadSizeBytes;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long startTime = System.currentTimeMillis();
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();

        // Handle CORS preflight
        if (method.equals("OPTIONS")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // 1. Authenticate
        String rawKey = HttpExchangeUtils.extractApiKey(exchange);
        if (rawKey == null) {
            HttpExchangeUtils.sendError(exchange, 401, "UNAUTHORIZED", "Missing API key in Authorization or X-API-Key header");
            metricsService.recordRequest(null, System.currentTimeMillis() - startTime, false, true, false);
            return;
        }

        Optional<SecurityContext> maybeContext = apiKeyService.authenticate(rawKey);
        if (maybeContext.isEmpty()) {
            HttpExchangeUtils.sendError(exchange, 401, "INVALID_KEY", "API key is invalid, expired, or has been revoked");
            metricsService.recordRequest(null, System.currentTimeMillis() - startTime, false, true, false);
            return;
        }

        SecurityContext ctx = maybeContext.get();

        // 2. Rate limit check
        RateLimiterService.RateLimitDecision decision = rateLimiterService.checkLimit(ctx);
        if (!decision.allowed()) {
            exchange.getResponseHeaders().set("Retry-After", String.valueOf((decision.retryAfterMs() + 999) / 1000));
            HttpExchangeUtils.sendError(exchange, 429, "RATE_LIMITED", "Project rate limit exceeded. Retry in " + decision.retryAfterMs() + "ms");
            metricsService.recordRequest(ctx.projectId(), System.currentTimeMillis() - startTime, false, true, true);
            return;
        }

        // 3. Bind to tenant storage engine (database 0 default)
        TenantStorageEngine tenantEngine = new TenantStorageEngine(rootEngine, ctx.projectId(), 0);

        try {
            if (path.startsWith("/v1/keys/")) {
                handleKeysEndpoint(exchange, method, path, tenantEngine, ctx);
            } else if (path.equals("/v1/mget")) {
                handleMGet(exchange, method, tenantEngine, ctx);
            } else if (path.equals("/v1/mset")) {
                handleMSet(exchange, method, tenantEngine, ctx);
            } else if (path.equals("/v1/scan")) {
                handleScan(exchange, method, tenantEngine, ctx);
            } else if (path.equals("/v1/pipeline")) {
                handlePipeline(exchange, method, tenantEngine, ctx);
            } else if (path.equals("/v1/dbsize")) {
                handleDbSize(exchange, method, tenantEngine, ctx);
            } else if (path.equals("/v1/flushdb")) {
                handleFlushDb(exchange, method, tenantEngine, ctx);
            } else {
                HttpExchangeUtils.sendError(exchange, 404, "NOT_FOUND", "Endpoint not found: " + path);
            }
            metricsService.recordRequest(ctx.projectId(), System.currentTimeMillis() - startTime, true, false, false);
        } catch (SecurityException se) {
            HttpExchangeUtils.sendError(exchange, 403, "FORBIDDEN", se.getMessage());
            metricsService.recordRequest(ctx.projectId(), System.currentTimeMillis() - startTime, false, true, false);
        } catch (IllegalArgumentException iae) {
            HttpExchangeUtils.sendError(exchange, 400, "BAD_REQUEST", iae.getMessage());
            metricsService.recordRequest(ctx.projectId(), System.currentTimeMillis() - startTime, false, true, false);
        } catch (Exception e) {
            log.error("Internal error handling request: {}", path, e);
            HttpExchangeUtils.sendError(exchange, 500, "INTERNAL_ERROR", "Internal server error: " + e.getMessage());
            metricsService.recordRequest(ctx.projectId(), System.currentTimeMillis() - startTime, false, true, false);
        }
    }

    private void handleKeysEndpoint(HttpExchange exchange, String method, String path, TenantStorageEngine engine, SecurityContext ctx) throws IOException {
        String subpath = path.substring("/v1/keys/".length());
        if (subpath.isBlank()) {
            throw new IllegalArgumentException("Key cannot be empty");
        }

        // Sub-actions: /incr, /expire, /ttl
        if (subpath.endsWith("/incr") && method.equals("POST")) {
            ctx.checkPermission(Permission.WRITE);
            String key = URLDecoder.decode(subpath.substring(0, subpath.length() - "/incr".length()), StandardCharsets.UTF_8);
            Map<String, String> query = HttpExchangeUtils.parseQueryParams(exchange.getRequestURI());
            long delta = 1;
            if (query.containsKey("delta")) {
                delta = Long.parseLong(query.get("delta"));
            } else {
                String body = HttpExchangeUtils.readBodyString(exchange, 1024);
                if (!body.isBlank()) {
                    JsonNode json = JsonUtils.readTree(body);
                    if (json.has("delta")) delta = json.get("delta").asLong();
                }
            }
            long newVal = engine.incrBy(key, delta);
            HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "key", key, "value", newVal));
            return;
        }

        if (subpath.endsWith("/expire") && method.equals("POST")) {
            ctx.checkPermission(Permission.WRITE);
            String key = URLDecoder.decode(subpath.substring(0, subpath.length() - "/expire".length()), StandardCharsets.UTF_8);
            Map<String, String> query = HttpExchangeUtils.parseQueryParams(exchange.getRequestURI());
            long seconds = 0;
            if (query.containsKey("seconds")) {
                seconds = Long.parseLong(query.get("seconds"));
            } else {
                String body = HttpExchangeUtils.readBodyString(exchange, 1024);
                if (!body.isBlank()) {
                    JsonNode json = JsonUtils.readTree(body);
                    if (json.has("seconds")) seconds = json.get("seconds").asLong();
                }
            }
            boolean ok = engine.expire(key, seconds);
            HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "key", key, "applied", ok));
            return;
        }

        if (subpath.endsWith("/ttl") && method.equals("GET")) {
            ctx.checkPermission(Permission.READ);
            String key = URLDecoder.decode(subpath.substring(0, subpath.length() - "/ttl".length()), StandardCharsets.UTF_8);
            long ttl = engine.ttl(key);
            HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "key", key, "ttl", ttl));
            return;
        }

        String key = URLDecoder.decode(subpath, StandardCharsets.UTF_8);

        switch (method) {
            case "GET" -> {
                ctx.checkPermission(Permission.READ);
                String val = engine.get(key);
                if (val == null) {
                    HttpExchangeUtils.sendError(exchange, 404, "KEY_NOT_FOUND", "Key not found: " + key);
                } else {
                    HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "key", key, "value", val));
                }
            }
            case "POST" -> {
                ctx.checkPermission(Permission.WRITE);
                String body = HttpExchangeUtils.readBodyString(exchange, maxPayloadSizeBytes);
                String valueToSet = body;
                long exSeconds = 0;
                long pxMillis = 0;
                boolean nx = false;
                boolean xx = false;

                // Check query params
                Map<String, String> query = HttpExchangeUtils.parseQueryParams(exchange.getRequestURI());
                if (query.containsKey("ex")) exSeconds = Long.parseLong(query.get("ex"));
                if (query.containsKey("px")) pxMillis = Long.parseLong(query.get("px"));
                if (query.containsKey("nx")) nx = Boolean.parseBoolean(query.get("nx"));
                if (query.containsKey("xx")) xx = Boolean.parseBoolean(query.get("xx"));

                // Or check JSON body
                if (body.startsWith("{") && body.endsWith("}")) {
                    try {
                        JsonNode json = JsonUtils.readTree(body);
                        if (json.has("value")) {
                            JsonNode vNode = json.get("value");
                            valueToSet = vNode.isTextual() ? vNode.asText() : JsonUtils.toJson(vNode);
                        }
                        if (json.has("ex")) exSeconds = json.get("ex").asLong();
                        if (json.has("px")) pxMillis = json.get("px").asLong();
                        if (json.has("nx")) nx = json.get("nx").asBoolean();
                        if (json.has("xx")) xx = json.get("xx").asBoolean();
                    } catch (Exception ignored) {}
                }

                SetOptions options = new SetOptions(exSeconds, pxMillis, nx, xx, false);
                boolean setSuccess = engine.set(key, valueToSet, options);
                HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", setSuccess, "key", key));
            }
            case "DELETE" -> {
                ctx.checkPermission(Permission.WRITE);
                boolean deleted = engine.delete(key);
                HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "key", key, "deleted", deleted));
            }
            default -> HttpExchangeUtils.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method " + method + " not allowed on /v1/keys");
        }
    }

    private void handleMGet(HttpExchange exchange, String method, TenantStorageEngine engine, SecurityContext ctx) throws IOException {
        if (!method.equals("POST")) {
            HttpExchangeUtils.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "MGET requires POST");
            return;
        }
        ctx.checkPermission(Permission.READ);
        String body = HttpExchangeUtils.readBodyString(exchange, maxPayloadSizeBytes);
        JsonNode json = JsonUtils.readTree(body);
        if (!json.has("keys") || !json.get("keys").isArray()) {
            throw new IllegalArgumentException("Expected JSON body: {\"keys\": [\"k1\", \"k2\"]}");
        }

        List<String> keys = new ArrayList<>();
        json.get("keys").forEach(n -> keys.add(n.asText()));
        List<String> values = engine.mget(keys);

        Map<String, String> results = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            results.put(keys.get(i), i < values.size() ? values.get(i) : null);
        }

        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "results", results));
    }

    private void handleMSet(HttpExchange exchange, String method, TenantStorageEngine engine, SecurityContext ctx) throws IOException {
        if (!method.equals("POST")) {
            HttpExchangeUtils.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "MSET requires POST");
            return;
        }
        ctx.checkPermission(Permission.WRITE);
        String body = HttpExchangeUtils.readBodyString(exchange, maxPayloadSizeBytes);
        JsonNode json = JsonUtils.readTree(body);
        if (!json.has("entries") || !json.get("entries").isObject()) {
            throw new IllegalArgumentException("Expected JSON body: {\"entries\": {\"k1\": \"v1\", \"k2\": \"v2\"}}");
        }

        Map<String, String> entries = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = json.get("entries").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            entries.put(field.getKey(), field.getValue().asText());
        }

        engine.mset(entries);
        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "count", entries.size()));
    }

    private void handleScan(HttpExchange exchange, String method, TenantStorageEngine engine, SecurityContext ctx) throws IOException {
        ctx.checkPermission(Permission.READ);
        Map<String, String> params = HttpExchangeUtils.parseQueryParams(exchange.getRequestURI());
        String cursor = params.getOrDefault("cursor", "0");
        String pattern = params.getOrDefault("pattern", "*");
        int count = Integer.parseInt(params.getOrDefault("count", "10"));

        ScanResult result = engine.scan(cursor, pattern, count);
        HttpExchangeUtils.sendJson(exchange, 200, Map.of(
                "success", true,
                "cursor", result.nextCursor(),
                "keys", result.keys()
        ));
    }

    private void handlePipeline(HttpExchange exchange, String method, TenantStorageEngine engine, SecurityContext ctx) throws IOException {
        if (!method.equals("POST")) {
            HttpExchangeUtils.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Pipeline requires POST");
            return;
        }
        String body = HttpExchangeUtils.readBodyString(exchange, maxPayloadSizeBytes);
        JsonNode json = JsonUtils.readTree(body);
        if (!json.isArray()) {
            throw new IllegalArgumentException("Expected JSON array of commands: [{\"cmd\": \"SET\", \"args\": [\"k\", \"v\"]}]");
        }

        List<Object> results = new ArrayList<>();
        for (JsonNode cmdNode : json) {
            String cmd = cmdNode.get("cmd").asText().toUpperCase();
            JsonNode argsNode = cmdNode.get("args");
            List<String> args = new ArrayList<>();
            if (argsNode != null && argsNode.isArray()) {
                argsNode.forEach(a -> args.add(a.asText()));
            }

            switch (cmd) {
                case "GET" -> {
                    ctx.checkPermission(Permission.READ);
                    results.add(engine.get(args.get(0)));
                }
                case "SET" -> {
                    ctx.checkPermission(Permission.WRITE);
                    boolean ok = engine.set(args.get(0), args.get(1));
                    results.add(ok ? "OK" : null);
                }
                case "DEL", "DELETE" -> {
                    ctx.checkPermission(Permission.WRITE);
                    results.add(engine.delete(args.get(0)));
                }
                case "INCR" -> {
                    ctx.checkPermission(Permission.WRITE);
                    results.add(engine.incr(args.get(0)));
                }
                case "EXISTS" -> {
                    ctx.checkPermission(Permission.READ);
                    results.add(engine.exists(args.get(0)));
                }
                default -> results.add("ERR unsupported pipeline command: " + cmd);
            }
        }

        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "results", results));
    }

    private void handleDbSize(HttpExchange exchange, String method, TenantStorageEngine engine, SecurityContext ctx) throws IOException {
        ctx.checkPermission(Permission.READ);
        long size = engine.dbSize();
        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "dbsize", size));
    }

    private void handleFlushDb(HttpExchange exchange, String method, TenantStorageEngine engine, SecurityContext ctx) throws IOException {
        if (!method.equals("POST")) {
            HttpExchangeUtils.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "FLUSHDB requires POST");
            return;
        }
        ctx.checkPermission(Permission.ADMIN);
        engine.flushDb();
        HttpExchangeUtils.sendJson(exchange, 200, Map.of("success", true, "message", "Tenant database flushed successfully"));
    }
}
