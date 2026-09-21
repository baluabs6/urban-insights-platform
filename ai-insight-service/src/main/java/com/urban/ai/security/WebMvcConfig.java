package com.urban.ai.security;

import com.urban.ai.ratelimit.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
                );
    }
}
