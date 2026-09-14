package com.urban.ai.security;

import com.urban.ai.ratelimit.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every LLM-backed "ops tool" endpoint requires the admin tier — these are
 * meant for city-official/dashboard use, not general public callers.
 * /api/ai/complaint-status is deliberately EXCLUDED from admin-gating: it's
 * the citizen-facing chatbot, protected instead by the ownership check in
 * ComplaintStatusChatService (matching citizenId, not API-key tier) — but it
 * DOES still get the distributed rate limiter below (aiEndpoints bucket),
 * since it's still an LLM call regardless of who's allowed to call it.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminOnlyInterceptor adminOnlyInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminOnlyInterceptor)
                .addPathPatterns(
                        "/api/ai/sla-escalations",
                        "/api/ai/classify-complaint",
                        "/api/ai/duplicate-check",
                        "/api/ai/compare-zones",
                        "/api/ai/anomaly-explanation/**",
                        "/api/ai/city-briefing",
                        "/api/ai/hotspots",
                        "/api/ai/verify-photo",
                        "/api/ai/sentiment",
                        "/api/ai/root-cause",
                        "/api/ai/classification-feedback",
                        "/api/insights/ask"
                );

        // Rate limiting is intentionally checked AFTER the admin-tier check above
        // (interceptors run in registration order) — a request that fails auth
        // shouldn't also consume shared rate-limit quota.
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/api/insights/ask",
                        "/api/ai/classify-complaint",
                        "/api/ai/duplicate-check",
                        "/api/ai/compare-zones",
                        "/api/ai/anomaly-explanation/**",
                        "/api/ai/complaint-status",
                        "/api/ai/verify-photo",
                        "/api/ai/sentiment",
                        "/api/ai/root-cause",
                        "/api/ai/voice-complaint"
                        // /api/ai/city-briefing, /api/ai/sla-escalations and /api/ai/hotspots are
                        // excluded: all three read from Redis/cache rather than calling the LLM on
                        // every request (see GenAiController javadoc on each).
                );
    }
}
