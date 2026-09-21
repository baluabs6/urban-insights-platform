package com.urban.ai.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final DistributedRateLimiter rateLimiter;

    @Value("${ratelimit.rag-query.limit:10}")
    private int ragQueryLimit;
    @Value("${ratelimit.rag-query.window-seconds:60}")
    private long ragQueryWindowSeconds;

    @Value("${ratelimit.ai-endpoints.limit:30}")
    private int aiEndpointsLimit;
    @Value("${ratelimit.ai-endpoints.window-seconds:60}")
    private long aiEndpointsWindowSeconds;

    public static final String BUCKET_RAG_QUERY = "ragQuery";
    public static final String BUCKET_AI_ENDPOINTS = "aiEndpoints";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String bucket = path.equals("/api/insights/ask") ? BUCKET_RAG_QUERY : BUCKET_AI_ENDPOINTS;

        int limit = bucket.equals(BUCKET_RAG_QUERY) ? ragQueryLimit : aiEndpointsLimit;
        Duration window = Duration.ofSeconds(bucket.equals(BUCKET_RAG_QUERY) ? ragQueryWindowSeconds : aiEndpointsWindowSeconds);

        if (!rateLimiter.tryAcquire(bucket, limit, window)) {
            response.setStatus(HttpServletResponse.SC_TOO_MANY_REQUESTS);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate limit exceeded, try again shortly\"}");
            return false;
        }
        return true;
    }
}
