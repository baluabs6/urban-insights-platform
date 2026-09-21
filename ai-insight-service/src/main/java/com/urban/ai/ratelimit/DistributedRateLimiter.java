package com.urban.ai.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedRateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;

    public boolean tryAcquire(String bucketName, int limitForPeriod, Duration window) {
        long windowIndex = Instant.now().getEpochSecond() / window.getSeconds();
        String key = "ratelimit:" + bucketName + ":" + windowIndex;

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, window);
            }
            boolean allowed = count != null && count <= limitForPeriod;
            if (!allowed) {
                log.warn("Rate limit exceeded for bucket '{}': {}/{} in current window", bucketName, count, limitForPeriod);
            }
            return allowed;
        } catch (Exception e) {
            log.error("Distributed rate limiter unavailable (Redis error) — failing OPEN for bucket '{}': {}",
                    bucketName, e.getMessage());
            return true;
        }
    }
}
