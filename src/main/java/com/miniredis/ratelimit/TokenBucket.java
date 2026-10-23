package com.miniredis.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe Token Bucket rate limiter.
 */
public final class TokenBucket {

    private final long capacity;
    private final double refillRatePerNano;

    private final AtomicLong availableTokens;
    private final AtomicLong lastRefillTime;

    public TokenBucket(long capacity, double refillTokensPerSecond) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        if (refillTokensPerSecond <= 0) throw new IllegalArgumentException("Refill rate must be > 0");

        this.capacity = capacity;
        this.refillRatePerNano = refillTokensPerSecond / 1_000_000_000.0;
        this.availableTokens = new AtomicLong(capacity);
        this.lastRefillTime = new AtomicLong(System.nanoTime());
    }

    public synchronized boolean tryConsume(int tokens) {
        refill();
        long current = availableTokens.get();
        if (current >= tokens) {
            availableTokens.set(current - tokens);
            return true;
        }
        return false;
    }

    public synchronized long getEstimatedWaitMs(int tokens) {
        long current = availableTokens.get();
        if (current >= tokens) return 0;
        long needed = tokens - current;
        double waitNanos = needed / refillRatePerNano;
        return Math.max(1, (long) (waitNanos / 1_000_000.0));
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillTime.get();
        long elapsedNanos = now - last;
        if (elapsedNanos > 0) {
            double newTokens = elapsedNanos * refillRatePerNano;
            if (newTokens > 0) {
                long current = availableTokens.get();
                long updated = Math.min(capacity, (long) (current + newTokens));
                availableTokens.set(updated);
                lastRefillTime.set(now);
            }
        }
    }
}
