package com.xnlp.server.tenant;

import com.xnlp.server.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds tenant identity for the complete lifetime of an HTTP request.
 *
 * <p>When API-key security is enabled, the tenant is derived exclusively from
 * the authenticated principal. With security disabled, the tenant header is
 * useful for local development and defaults to the configured tenant.</p>
 */
public final class TenantContextFilter extends OncePerRequestFilter {

    private final SecurityProperties properties;

    public TenantContextFilter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId = resolveTenant(request);
        TenantContext.setTenantId(tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String resolveTenant(HttpServletRequest request) {
        if (properties.isEnabled()) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() != null
                    && !"anonymousUser".equals(authentication.getPrincipal())) {
                return TenantContext.normalize(authentication.getName());
            }
            return properties.getDefaultTenantId();
        }
        String requested = request.getHeader(properties.getTenantHeaderName());
        return requested == null || requested.isBlank()
                ? properties.getDefaultTenantId() : TenantContext.normalize(requested);
    }
}
