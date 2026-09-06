package com.urban.complaint.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Minimal service-to-service auth: every request must carry a shared secret in
 * the X-API-Key header. This is intentionally simple (not OAuth2/JWT with
 * per-user roles) — enough to stop the "anyone can curl citizen PII" gap while
 * keeping the reference project easy to run. Swap for OAuth2 + per-role scopes
 * (citizen vs city-official vs admin) before any real deployment.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    @Value("${security.api-key:change-me-in-prod}")
    private String expectedApiKey;

    @Value("${security.enabled:true}")
    private boolean enabled;

    private static final List<String> PUBLIC_PATHS = List.of("/actuator/health", "/actuator/info");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!enabled || PUBLIC_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader("X-API-Key");
        if (provided == null || !provided.equals(expectedApiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid X-API-Key header\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
