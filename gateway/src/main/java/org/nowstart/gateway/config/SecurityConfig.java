package org.nowstart.gateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomAuthoritiesFilter customAuthoritiesFilter;
    private final GatewayAuthenticationEntryPoint authenticationEntryPoint;
    private final GatewayAuthorizationRules authorizationRules;

    @RefreshScope
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .headers(headers -> headers
                        .frameOptions(ServerHttpSecurity.HeaderSpec.FrameOptionsSpec::disable)
                        .contentSecurityPolicy(csp -> csp.policyDirectives("upgrade-insecure-requests; frame-ancestors 'self'"))
                )
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeExchange(authorizationRules::configure)
                .httpBasic(Customizer.withDefaults())
                .oauth2Login(Customizer.withDefaults())
                .addFilterAfter(customAuthoritiesFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
