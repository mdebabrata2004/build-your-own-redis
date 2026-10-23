package com.miniredis;

import com.miniredis.ratelimit.RateLimiterService;
import com.miniredis.ratelimit.TokenBucket;
import com.miniredis.security.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    @DisplayName("Token bucket permits requests within capacity and blocks over-limit requests")
    void tokenBucketRateLimiting() {
        // Capacity 5, refill 10 tokens per second
        TokenBucket bucket = new TokenBucket(5, 10);

        // First 5 requests should succeed
        for (int i = 0; i < 5; i++) {
            assertThat(bucket.tryConsume(1))
                    .as("Request %d should be allowed", i)
                    .isTrue();
        }

        // 6th request immediately after must be blocked
        assertThat(bucket.tryConsume(1))
                .as("Exhausted bucket should block request")
                .isFalse();

        assertThat(bucket.getEstimatedWaitMs(1)).isGreaterThan(0);
    }

    @Test
    @DisplayName("RateLimiterService tracks rate limits per project context")
    void rateLimiterServiceDecision() {
        RateLimiterService service = new RateLimiterService();

        SecurityContext ctx = new SecurityContext(
                "proj_limited",
                "acc_1",
                "key_1",
                "Test Key",
                Set.of("read"),
                10, // 10 rps
                10, // 10 burst
                1024 * 1024
        );

        for (int i = 0; i < 10; i++) {
            RateLimiterService.RateLimitDecision decision = service.checkLimit(ctx);
            assertThat(decision.allowed()).isTrue();
        }

        RateLimiterService.RateLimitDecision blocked = service.checkLimit(ctx);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterMs()).isGreaterThan(0);
    }
}
