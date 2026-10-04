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

    /** MCP server endpoints (Spring AI MCP, SSE transport) expose tool access, so they need the admin key. */
    private static final List<String> ADMIN_ONLY_PREFIXES = List.of("/sse", "/mcp");

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

        if (isAdminOnlyPath(request.getRequestURI()) && !TIER_ADMIN.equals(request.getAttribute(TIER_ATTRIBUTE))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"this endpoint requires the admin API key\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isAdminOnlyPath(String uri) {
        return ADMIN_ONLY_PREFIXES.stream().anyMatch(prefix -> uri.equals(prefix) || uri.startsWith(prefix + "/"));
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
