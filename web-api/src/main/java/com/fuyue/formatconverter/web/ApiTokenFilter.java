package com.fuyue.formatconverter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ApiTokenFilter extends OncePerRequestFilter {
    private final String apiToken;

    public ApiTokenFilter(String apiToken) {
        this.apiToken = apiToken == null ? "" : apiToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // FilterRegistrationBean maps this filter only to protected API URL
        // patterns. Once the container selects it, never reinterpret the path:
        // servlet context paths and matrix parameters may be normalized
        // differently by Spring MVC and must not create an authentication gap.
        return apiToken.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Format-Converter-Token");
        String authorization = request.getHeader("Authorization");
        if ((supplied == null || supplied.isBlank()) && authorization != null && authorization.startsWith("Bearer ")) {
            supplied = authorization.substring("Bearer ".length());
        }
        if (!matches(supplied)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API token");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String supplied) {
        if (supplied == null) return false;
        return MessageDigest.isEqual(apiToken.getBytes(StandardCharsets.UTF_8),
                supplied.trim().getBytes(StandardCharsets.UTF_8));
    }
}
