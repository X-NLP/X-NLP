package com.xnlp.server.quota;

import com.xnlp.server.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Applies tenant request and concurrency quotas after tenant identity has been bound. */
public final class QuotaEnforcementFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    private final QuotaGuard quotaGuard;

    /** Constructor intended for explicit insertion into the configured security filter chain. */
    public QuotaEnforcementFilter(QuotaGuard quotaGuard) {
        this.quotaGuard = Objects.requireNonNull(quotaGuard, "quotaGuard");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health")
                || path.equals("/healthz")
                || path.equals("/ok")
                || path.equals("/livez")
                || path.equals("/readyz")
                || path.equals("/startupz")
                || path.equals("/error")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/actuator")
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = requestId(request);
        try (QuotaGuard.RequestLease ignored = quotaGuard.acquire(TenantContext.currentTenantId(), requestId)) {
            filterChain.doFilter(request, response);
        } catch (QuotaExceededException exception) {
            writeQuotaExceeded(response, requestId, exception);
        }
    }

    private static String requestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
    }

    private static void writeQuotaExceeded(HttpServletResponse response, String requestId,
                                           QuotaExceededException exception) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(exception.retryAfterSeconds()));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":429,\"error\":\"" + exception.code()
                + "\",\"message\":\"" + exception.getMessage()
                + "\",\"retryAfterSeconds\":" + exception.retryAfterSeconds()
                + ",\"requestId\":\"" + jsonEscape(requestId) + "\"}");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
