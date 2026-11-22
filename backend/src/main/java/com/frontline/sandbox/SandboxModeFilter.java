package com.frontline.sandbox;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class SandboxModeFilter extends OncePerRequestFilter {
    private final SandboxProperties properties;
    public SandboxModeFilter(SandboxProperties properties) { this.properties = properties; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!properties.enabled() && path.startsWith("/api/sandbox/")) { response.setStatus(404); return; }
        if (properties.enabled() && path.startsWith("/api/admin/")) { response.setStatus(403); return; }
        chain.doFilter(request,response);
    }
}
