package com.urban.ai.security;

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
 * Two-tier shared-secret auth: every request must carry the key either as
 * X-API-Key, OR as a standard "Authorization: Bearer <key>" header (accepted
 * so tools with universal bearer-token support — notably Prometheus's
 * scrape_config bearer_token field — can authenticate without needing a
 * custom-header extension). A request carrying the ADMIN key gets request
 * attribute "apiKeyTier"="admin"; the general-purpose key gets "public".
 * AdminOnlyInterceptor uses that attribute to gate privileged/PII-exposing
 * endpoints to the admin tier.
 *
 * This is intentionally still simple (two static secrets, not OAuth2/JWT with
 * real per-user identity or scopes) — it stops "one key does everything,
 * including reading any citizen's PII" without the complexity of a full auth
 * server. Swap for OAuth2 + per-role scopes (citizen vs city-official vs
 * admin) before any real deployment.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    @Value("${security.api-key:change-me-in-prod}")
    private String publicApiKey;

    @Value("${security.admin-api-key:change-me-admin-in-prod}")
    private String adminApiKey;

    @Value("${security.enabled:true}")
    private boolean enabled;

    public static final String TIER_ATTRIBUTE = "apiKeyTier";
    public static final String TIER_ADMIN = "admin";
    public static final String TIER_PUBLIC = "public";

    private static final List<String> PUBLIC_PATHS = List.of("/actuator/health", "/actuator/info");
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!enabled || PUBLIC_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String provided = extractApiKey(request);
        if (provided != null && provided.equals(adminApiKey)) {
            request.setAttribute(TIER_ATTRIBUTE, TIER_ADMIN);
        } else if (provided != null && provided.equals(publicApiKey)) {
            request.setAttribute(TIER_ATTRIBUTE, TIER_PUBLIC);
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid API key (X-API-Key or Authorization: Bearer)\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private String extractApiKey(HttpServletRequest request) {
        String headerKey = request.getHeader("X-API-Key");
        if (headerKey != null) return headerKey;

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
