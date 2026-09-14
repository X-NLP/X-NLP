package com.xnlp.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Optional stateless API-key security for the HTTP control plane.
 *
 * <p>Health probes, OpenAPI resources and CORS preflight remain public so the
 * service can be operated by a load balancer and inspected by operators. All
 * application APIs require a valid key when the feature is enabled.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class ApiKeySecurityConfiguration {

    private static final List<String> PUBLIC_PATHS = List.of(
            "/health", "/healthz", "/livez", "/readyz", "/startupz", "/ok",
            "/actuator/health", "/actuator/health/**", "/swagger-ui.html",
            "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"
    );

    private final SecurityProperties properties;

    public ApiKeySecurityConfiguration(SecurityProperties properties) {
        this.properties = properties;
        properties.validate();
    }

    /**
     * Prevents Spring Boot from creating a random form-login user/password.
     * X-NLP authenticates at the API boundary with the filter above, so a
     * servlet user store would be both unused and misleading in production.
     */
    @Bean
    AuthenticationProvider apiKeyAuthenticationProvider() {
        return new AuthenticationProvider() {
            @Override
            public org.springframework.security.core.Authentication authenticate(
                    org.springframework.security.core.Authentication authentication) {
                throw new BadCredentialsException("X-NLP uses API-key authentication");
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return false;
            }
        };
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(requestCache -> requestCache.disable())
                .securityContext(securityContext -> securityContext.requireExplicitSave(false));

        if (!properties.isEnabled()) {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedEntryPoint()))
                .addFilterBefore(new ApiKeyAuthenticationFilter(properties), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authenticationException) -> writeError(
                response, HttpStatusCode.UNAUTHORIZED, "unauthorized", "A valid API key is required");
    }

    private static void writeError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"timestamp\":\"" + Instant.now()
                + "\",\"status\":" + status
                + ",\"error\":\"" + error
                + "\",\"message\":\"" + message + "\"}");
    }

    private static final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

        private final SecurityProperties properties;

        private ApiKeyAuthenticationFilter(SecurityProperties properties) {
            this.properties = properties;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return !properties.isEnabled()
                    || "OPTIONS".equalsIgnoreCase(request.getMethod())
                    || PUBLIC_PATHS.stream().anyMatch(publicPath -> matchesPath(path, publicPath));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String candidate = request.getHeader(properties.getHeaderName());
            if (candidate == null || candidate.isBlank()) {
                String authorization = request.getHeader("Authorization");
                if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    candidate = authorization.substring(7).trim();
                }
            }

            if (!properties.matches(candidate)) {
                writeError(response, HttpStatusCode.UNAUTHORIZED, "unauthorized", "A valid API key is required");
                return;
            }

            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    "api-key", null, List.of(new SimpleGrantedAuthority("ROLE_API")));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        private static boolean matchesPath(String path, String configuredPath) {
            return path.equals(configuredPath)
                    || (configuredPath.endsWith("/**")
                    && path.startsWith(configuredPath.substring(0, configuredPath.length() - 3)));
        }
    }

    private static final class HttpStatusCode {
        private static final int UNAUTHORIZED = 401;

        private HttpStatusCode() {
        }
    }
}
