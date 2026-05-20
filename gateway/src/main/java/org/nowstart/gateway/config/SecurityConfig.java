package org.nowstart.gateway.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.nowstart.gateway.data.AuthorizeExchangeProperties;
import org.nowstart.gateway.data.Role;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@RefreshScope
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthorizeExchangeProperties authorizeProperties;
    private final CustomAuthoritiesFilter customAuthoritiesFilter = new CustomAuthoritiesFilter();
    private final PathPatternParser pathPatternParser = new PathPatternParser();

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .headers(headers -> headers
                        .frameOptions(ServerHttpSecurity.HeaderSpec.FrameOptionsSpec::disable)
                        .contentSecurityPolicy(csp -> csp.policyDirectives("upgrade-insecure-requests; frame-ancestors 'self'"))
                )
                .authorizeExchange(this::configureAuthorization)
                .httpBasic(Customizer.withDefaults())
                .oauth2Login(Customizer.withDefaults())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterAfter(customAuthoritiesFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public ReactiveUserDetailsService reactiveUserDetailsService(SecurityProperties securityProperties) {
        SecurityProperties.User user = securityProperties.getUser();
        return new MapReactiveUserDetailsService(User.withUsername(user.getName())
                .password(user.getPassword())
                .roles(user.getRoles().toArray(String[]::new))
                .build());
    }

    private void configureAuthorization(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {
        List<AuthorizeExchangeProperties.PathRule> sortedRules = authorizeProperties.getRules().stream()
                .sorted((r1, r2) -> {
                    PathPattern p1 = pathPatternParser.parse(r1.path());
                    PathPattern p2 = pathPatternParser.parse(r2.path());
                    return PathPattern.SPECIFICITY_COMPARATOR.compare(p1, p2);
                })
                .toList();

        for (AuthorizeExchangeProperties.PathRule rule : sortedRules) {
            if (CollectionUtils.isEmpty(rule.roles())) {
                exchanges.pathMatchers(rule.path()).permitAll();
            } else {
                exchanges.pathMatchers(rule.path()).hasAnyRole(Role.acceptedNames(rule.roles()));
            }
        }

        exchanges.anyExchange().hasRole(Role.ADMINISTRATORS.name());
    }
}
