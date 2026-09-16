package com.xnlp.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.server.dto.ApiErrorResponse;
import com.xnlp.server.security.ApiKeyService;
import com.xnlp.server.security.AuthenticationMode;
import com.xnlp.server.security.TenantMembershipRepository;
import com.xnlp.server.security.TenantRole;
import com.xnlp.server.security.XnlpPrincipal;
import com.xnlp.server.tenant.TenantContext;
import com.xnlp.server.tenant.TenantContextFilter;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class ApiKeySecurityConfiguration {

    private static final List<String> PUBLIC_PATHS = List.of(
            "/health", "/healthz", "/livez", "/readyz", "/startupz", "/ok",
            "/actuator/health", "/actuator/health/**", "/swagger-ui.html",
            "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"
    );

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final TenantMembershipRepository memberships;
    private final ApiKeyService apiKeys;

    public ApiKeySecurityConfiguration(
            SecurityProperties properties,
            ObjectMapper objectMapper,
            TenantMembershipRepository memberships,
            ApiKeyService apiKeys) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.memberships = memberships;
        this.apiKeys = apiKeys;
        properties.validate();
    }

    @Bean
    AuthenticationProvider apiKeyAuthenticationProvider() {
        return new AuthenticationProvider() {
            @Override
            public org.springframework.security.core.Authentication authenticate(
                    org.springframework.security.core.Authentication authentication) {
                throw new BadCredentialsException("X-NLP authenticates at the HTTP boundary");
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return false;
            }
        };
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        AuthenticationMode mode = properties.effectiveMode();
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(requestCache -> requestCache.disable())
                .securityContext(securityContext -> securityContext.requireExplicitSave(false))
                .addFilterAfter(new TenantContextFilter(properties), AnonymousAuthenticationFilter.class);

        if (mode == AuthenticationMode.DISABLED) {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
            return http.build();
        }

        String admin = TenantRole.ADMIN.name();
        String developer = TenantRole.DEVELOPER.name();
        String viewer = TenantRole.VIEWER.name();
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS.toArray(String[]::new)).permitAll()
                        .requestMatchers("/api/v1/tenants/**", "/api/v1/api-keys/**",
                                "/api/v1/audit-events/**").hasRole(admin)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasRole(admin)
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole(admin, developer, viewer)
                        .requestMatchers("/api/v1/**").hasAnyRole(admin, developer)
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));

        if (mode.acceptsJwt()) {
            http.oauth2ResourceServer(resourceServer -> resourceServer
                    .jwt(jwt -> jwt
                            .decoder(jwtDecoder())
                            .jwtAuthenticationConverter(this::jwtAuthenticationToken))
                    .authenticationEntryPoint(unauthorizedEntryPoint()));
        }
        if (mode.acceptsApiKey()) {
            http.addFilterBefore(
                    new ApiKeyAuthenticationFilter(properties, apiKeys, objectMapper),
                    UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    private JwtDecoder jwtDecoder() {
        SecurityProperties.Jwt jwt = properties.getJwt();
        NimbusJwtDecoder decoder = jwt.getJwkSetUri() == null
                ? (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(jwt.getIssuerUri())
                : NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(jwt.getClockSkew());
        validators.add(timestampValidator);
        validators.add(new JwtIssuerValidator(jwt.getIssuerUri()));
        validators.add(new JwtAudienceValidator(jwt.getAudience()));
        validators.add(new JwtClaimValidator<>(jwt.getTenantClaim(), value -> value instanceof String text && !text.isBlank()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private JwtAuthenticationToken jwtAuthenticationToken(Jwt token) {
        SecurityProperties.Jwt jwt = properties.getJwt();
        String subject = token.getSubject();
        String tenantId = TenantContext.normalize(token.getClaimAsString(jwt.getTenantClaim()));
        Set<TenantRole> tokenRoles = jwt.parseRoles(token.getClaim(jwt.getRolesClaim()));
        Set<TenantRole> roles = memberships.find(tenantId, subject)
                .map(membership -> membership.roles())
                .orElse(tokenRoles);
        List<GrantedAuthority> authorities = roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.authority()))
                .toList();
        XnlpPrincipal principal = new XnlpPrincipal(subject, tenantId, roles, "jwt");
        return new JwtAuthenticationToken(token, principal, authorities);
    }

    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, ignored) -> writeError(
                request, response, 401, "authentication_required", "A valid credential is required");
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ignored) -> writeError(
                request, response, 403, "forbidden", "The authenticated principal is not permitted to perform this operation");
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status,
                            String error, String message) throws IOException {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(
                Instant.now(), status, error, message, null, null, requestId, null));
    }

    private static final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
        private final SecurityProperties properties;
        private final ApiKeyService apiKeys;
        private final ObjectMapper objectMapper;

        private ApiKeyAuthenticationFilter(
                SecurityProperties properties, ApiKeyService apiKeys, ObjectMapper objectMapper) {
            this.properties = properties;
            this.apiKeys = apiKeys;
            this.objectMapper = objectMapper;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            String authorization = request.getHeader("Authorization");
            return !properties.effectiveMode().acceptsApiKey()
                    || (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    && properties.effectiveMode().acceptsJwt())
                    || "OPTIONS".equalsIgnoreCase(request.getMethod())
                    || PUBLIC_PATHS.stream().anyMatch(publicPath -> matchesPath(path, publicPath));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String candidate = request.getHeader(properties.getHeaderName());
            if ((candidate == null || candidate.isBlank()) && !properties.effectiveMode().acceptsJwt()) {
                String authorization = request.getHeader("Authorization");
                if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    candidate = authorization.substring(7).trim();
                }
            }
            SecurityProperties.ApiKeyIdentity legacyIdentity = properties.identityFor(candidate);
            ApiKeyService.AuthenticatedApiKey managedIdentity = legacyIdentity == null
                    ? apiKeys.authenticate(candidate) : null;
            if (legacyIdentity == null && managedIdentity == null) {
                writeError(request, response, "A valid API key is required");
                return;
            }
            XnlpPrincipal principal = legacyIdentity != null
                    ? new XnlpPrincipal(legacyIdentity.subject(), legacyIdentity.tenantId(),
                    legacyIdentity.roles(), "api-key")
                    : new XnlpPrincipal("api-key:" + managedIdentity.id(), managedIdentity.tenantId(),
                    managedIdentity.roles(), "api-key");
            var authorities = principal.roles().stream()
                    .map(role -> new SimpleGrantedAuthority(role.authority())).toList();
            var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        private void writeError(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
            String requestId = request.getHeader("X-Request-ID");
            if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(
                    Instant.now(), 401, "authentication_required", message, null, null, requestId, null));
        }

        private static boolean matchesPath(String path, String configuredPath) {
            return path.equals(configuredPath)
                    || (configuredPath.endsWith("/**")
                    && path.startsWith(configuredPath.substring(0, configuredPath.length() - 3)));
        }
    }
}
