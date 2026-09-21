package com.urban.complaint.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
                        "/api/complaints/*"
                )
                ;
    }
}
