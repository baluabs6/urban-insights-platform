package com.urban.traffic.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminOnlyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Object tier = request.getAttribute(ApiKeyAuthFilter.TIER_ATTRIBUTE);
        if (!ApiKeyAuthFilter.TIER_ADMIN.equals(tier)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"this endpoint requires the admin API key\"}");
            return false;
        }
        return true;
    }
}
