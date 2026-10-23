package com.miniredis.ratelimit;

import com.miniredis.security.SecurityContext;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-project and per-key rate limiters.
 */
public final class RateLimiterService {

    public record RateLimitDecision(boolean allowed, long retryAfterMs) {}

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitDecision checkLimit(SecurityContext context) {
        if (context == null) {
            return new RateLimitDecision(true, 0);
        }

        // Admin bypass optional or apply standard limit
        int burst = Math.max(10, context.rateLimitBurst());
        int rps = Math.max(5, context.rateLimitRps());

        TokenBucket bucket = buckets.computeIfAbsent(
                context.projectId(),
                k -> new TokenBucket(burst, rps)
        );

        if (bucket.tryConsume(1)) {
            return new RateLimitDecision(true, 0);
        } else {
            long waitMs = bucket.getEstimatedWaitMs(1);
            return new RateLimitDecision(false, waitMs);
        }
    }

    public void reset(String projectId) {
        buckets.remove(projectId);
    }
}
