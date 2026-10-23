package com.miniredis.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Map;

public final class HealthHandler implements HttpHandler {

    private final long startTime = System.currentTimeMillis();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long uptime = System.currentTimeMillis() - startTime;
        Map<String, Object> resp = Map.of(
                "status", "UP",
                "version", "1.0.0",
                "uptimeMs", uptime,
                "jvmUptimeMs", ManagementFactory.getRuntimeMXBean().getUptime(),
                "javaVersion", System.getProperty("java.version")
        );
        HttpExchangeUtils.sendJson(exchange, 200, resp);
    }
}
