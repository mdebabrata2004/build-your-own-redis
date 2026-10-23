package com.miniredis.gateway;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class HttpExchangeUtils {

    private HttpExchangeUtils() {}

    public static void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
        byte[] bytes = JsonUtils.toJson(data).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int statusCode, String code, String message) throws IOException {
        Map<String, Object> body = Map.of(
                "success", false,
                "error", Map.of(
                        "code", code,
                        "message", message
                )
        );
        sendJson(exchange, statusCode, body);
    }

    public static String readBodyString(HttpExchange exchange, int maxBytes) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        int total = 0;
        while ((read = is.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalArgumentException("Payload size exceeds maximum allowed limit of " + maxBytes + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public static Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> queryPairs = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return queryPairs;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0 && idx < pair.length() - 1) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                queryPairs.put(key, val);
            } else if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                queryPairs.put(key, "");
            }
        }
        return queryPairs;
    }

    public static String extractApiKey(HttpExchange exchange) {
        // 1. Try Authorization header (Bearer ld_live_...)
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && !auth.isBlank()) {
            auth = auth.trim();
            if (auth.startsWith("Bearer ")) {
                return auth.substring(7).trim();
            }
            return auth;
        }

        // 2. Try X-API-Key header
        String xKey = exchange.getRequestHeaders().getFirst("X-API-Key");
        if (xKey != null && !xKey.isBlank()) {
            return xKey.trim();
        }

        // 3. Try query parameter ?apiKey=...
        Map<String, String> params = parseQueryParams(exchange.getRequestURI());
        String queryKey = params.get("apiKey");
        if (queryKey != null && !queryKey.isBlank()) {
            return queryKey.trim();
        }

        return null;
    }
}
