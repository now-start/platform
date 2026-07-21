package org.nowstart.gateway.config;

import java.util.regex.Pattern;
import org.nowstart.gateway.data.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String DEFAULT_OAUTH2_SUCCESS_LOCATION = "/admin";
    private static final String INVALID_BASIC_PASSWORD_MESSAGE =
            "spring.security.user.password must be an explicit BCrypt hash";
    private static final Pattern BCRYPT_PASSWORD_PATTERN = Pattern.compile(
            "\\{bcrypt\\}\\$2[aby]\\$(?:0[4-9]|[12]\\d|3[01])\\$[./0-9A-Za-z]{53}"
    );
    private static final String[] OAUTH2_LOGIN_PATHS = {
            "/oauth2/authorization/**",
            "/login/oauth2/code/**"
    };
    private static final String[] PUBLIC_PATHS = {
            "/actuator/**",
            "/nyang-nyang-bot/**"
    };
    private static final String[] USER_PATHS = {
            "/admin",
            "/admin/",
            "/admin/applications",
            "/admin/wallboard",
            "/admin/journal",
            "/admin/external",
            "/admin/assets/**",
            "/admin/*.*",
            "/admin/swagger-ui/**",
            "/*/v3/api-docs",
            "/admin/v3/api-docs/swagger-config"
    };

    private final GatewayAuthenticationEntryPoint authenticationEntryPoint;

    public SecurityConfig(
            GatewayAuthenticationEntryPoint authenticationEntryPoint,
            @Value("${spring.security.user.password}") String basicPassword
    ) {
        if (basicPassword == null || !BCRYPT_PASSWORD_PATTERN.matcher(basicPassword).matches()) {
            throw new IllegalStateException(INVALID_BASIC_PASSWORD_MESSAGE);
        }
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, RoleHierarchy roleHierarchy) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .headers(headers -> headers
                        .frameOptions(ServerHttpSecurity.HeaderSpec.FrameOptionsSpec::disable)
                        .contentSecurityPolicy(csp -> csp.policyDirectives("upgrade-insecure-requests; frame-ancestors 'self'"))
                )
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeExchange(this::authorizeExchange)
                .httpBasic(Customizer.withDefaults())
                .oauth2Login(oauth2 -> oauth2.authenticationSuccessHandler(oAuth2SuccessHandler()))
                .addFilterAfter(new CustomAuthoritiesFilter(roleHierarchy), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMINISTRATORS.name())
                .implies(Role.USERS.name())
                .build();
    }

    private void authorizeExchange(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {
        exchanges.pathMatchers(OAUTH2_LOGIN_PATHS).permitAll()
                .pathMatchers(USER_PATHS).hasRole(Role.USERS.name())
                .pathMatchers(PUBLIC_PATHS).permitAll()
                .anyExchange().hasRole(Role.ADMINISTRATORS.name());
    }

    private RedirectServerAuthenticationSuccessHandler oAuth2SuccessHandler() {
        RedirectServerAuthenticationSuccessHandler successHandler =
                new RedirectServerAuthenticationSuccessHandler(DEFAULT_OAUTH2_SUCCESS_LOCATION);
        successHandler.setRequestCache(new WebSessionServerRequestCache());
        return successHandler;
    }
}
