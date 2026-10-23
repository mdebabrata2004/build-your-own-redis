package com.miniredis.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * High-performance, concurrent usage and performance metrics service.
 */
public final class MetricsService {

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalHits = new LongAdder();
    private final LongAdder totalMisses = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder totalRateLimited = new LongAdder();
    private final LongAdder totalLatencyMs = new LongAdder();
    private final AtomicLong activeConnections = new AtomicLong(0);

    private final ConcurrentHashMap<String, LongAdder> projectRequests = new ConcurrentHashMap<>();

    public void recordRequest(String projectId, long latencyMs, boolean isHit, boolean isError, boolean isRateLimited) {
        totalRequests.increment();
        totalLatencyMs.add(latencyMs);

        if (isHit) totalHits.increment();
        else totalMisses.increment();

        if (isError) totalErrors.increment();
        if (isRateLimited) totalRateLimited.increment();

        if (projectId != null && !projectId.isBlank()) {
            projectRequests.computeIfAbsent(projectId, k -> new LongAdder()).increment();
        }
    }

    public void incrementConnections() { activeConnections.incrementAndGet(); }
    public void decrementConnections() { activeConnections.decrementAndGet(); }

    public Map<String, Object> getMetricsSnapshot(long totalKeys) {
        long reqs = totalRequests.sum();
        long hits = totalHits.sum();
        long misses = totalMisses.sum();
        long errors = totalErrors.sum();
        long rateLimited = totalRateLimited.sum();
        long latencySum = totalLatencyMs.sum();
        double avgLatency = reqs > 0 ? (double) latencySum / reqs : 0.0;
        double hitRatio = (hits + misses) > 0 ? (double) hits / (hits + misses) : 1.0;

        return Map.of(
                "totalRequests", reqs,
                "totalHits", hits,
                "totalMisses", misses,
                "hitRatio", Math.round(hitRatio * 1000.0) / 1000.0,
                "totalErrors", errors,
                "totalRateLimited", rateLimited,
                "averageLatencyMs", Math.round(avgLatency * 100.0) / 100.0,
                "activeConnections", activeConnections.get(),
                "totalKeysInMemory", totalKeys
        );
    }
}
