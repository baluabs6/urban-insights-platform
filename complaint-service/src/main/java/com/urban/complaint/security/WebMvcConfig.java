package com.urban.complaint.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Endpoints requiring the ADMIN API-key tier: anything that exposes complaint
 * PII in bulk (by zone/status/id), or performs a privileged write. Citizen-facing
 * submission (POST /api/complaints) stays on the public tier.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminOnlyInterceptor adminOnlyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminOnlyInterceptor)
                .addPathPatterns(
                        "/api/complaints/*/classification",
                        "/api/complaints/*/status",
                        "/api/complaints/needing-reclassification",
                        "/api/complaints/status/**",
                        "/api/complaints/zone/**",
                        "/api/complaints/urgent",
                        "/api/complaints/nearby",
                        "/api/complaints/*" // covers GET /{id} — bulk PII lookup by ID
                )
                // The citizen-facing submit endpoint is POST /api/complaints (no path
                // variable) — the "/api/complaints/*" pattern above only matches paths
                // WITH a segment after /complaints/, so submission is correctly excluded.
                ;
    }
}
