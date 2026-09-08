package com.urban.ai.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Fixed-window distributed rate limiter backed by Redis — replaces
 * Resilience4j's @RateLimiter for LLM-backed endpoints.
 *
 * Why this exists: Resilience4j's RateLimiter keeps its token bucket in each
 * JVM's own memory. Run 5 replicas of this service and each gets its own
 * independent 10/min budget — the *effective* ceiling is 5x whatever was
 * configured, silently, the moment you scale past one instance. Every caller
 * shares one Redis counter instead, so the limit means what it says
 * regardless of replica count.
 *
 * Trade-off versus Resilience4j: fixed windows allow a burst at the boundary
 * (up to 2x the limit across two adjacent windows, in the worst case) where a
 * sliding-window or token-bucket algorithm wouldn't. Acceptable here — this
 * is a cost-control backstop against a caller hammering the endpoint, not a
 * precision SLA guarantee. A Lua-scripted sliding-window-log implementation
 * would close that gap if it ever matters.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedRateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * @param bucketName logical limiter name, e.g. "ragQuery" or "aiEndpoints" —
     *                    shared across ALL callers and ALL instances of this service
     * @param limitForPeriod max requests allowed within the window
     * @param window duration of the fixed window
     * @return true if the request is allowed, false if the limit has been reached
     */
    public boolean tryAcquire(String bucketName, int limitForPeriod, Duration window) {
        long windowIndex = Instant.now().getEpochSecond() / window.getSeconds();
        String key = "ratelimit:" + bucketName + ":" + windowIndex;

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // First request in this window — set the expiry once, not on every increment.
                redisTemplate.expire(key, window);
            }
            boolean allowed = count != null && count <= limitForPeriod;
            if (!allowed) {
                log.warn("Rate limit exceeded for bucket '{}': {}/{} in current window", bucketName, count, limitForPeriod);
            }
            return allowed;
        } catch (Exception e) {
            // Fail OPEN: if Redis itself is down, a rate limiter that blocks every
            // request would turn a cache outage into a full outage of these
            // endpoints. Losing the rate limit temporarily is the safer failure mode
            // than losing the feature entirely — logged loudly so it's not silent.
            log.error("Distributed rate limiter unavailable (Redis error) — failing OPEN for bucket '{}': {}",
                    bucketName, e.getMessage());
            return true;
        }
    }
}
