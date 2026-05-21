package org.nowstart.gateway.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.nowstart.gateway.data.AuthorizeExchangeProperties;
import org.nowstart.gateway.data.Role;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@RefreshScope
@Component
@RequiredArgsConstructor
public class GatewayAuthorizationRules {

    private static final String[] OAUTH2_LOGIN_PATHS = {
            "/login",
            "/oauth2/authorization/**",
            "/login/oauth2/code/**",
            "/error"
    };

    private final AuthorizeExchangeProperties authorizeProperties;
    private final PathPatternParser pathPatternParser = new PathPatternParser();

    public void configure(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {
        exchanges.pathMatchers(OAUTH2_LOGIN_PATHS).permitAll();

        List<AuthorizeExchangeProperties.PathRule> sortedRules = authorizeProperties.getPathRules().stream()
                .sorted((r1, r2) -> {
                    PathPattern p1 = pathPatternParser.parse(r1.path());
                    PathPattern p2 = pathPatternParser.parse(r2.path());
                    return PathPattern.SPECIFICITY_COMPARATOR.compare(p1, p2);
                })
                .toList();

        for (AuthorizeExchangeProperties.PathRule rule : sortedRules) {
            if (CollectionUtils.isEmpty(rule.roles())) {
                permitAll(exchanges, rule);
            } else {
                requireRoles(exchanges, rule);
            }
        }

        exchanges.anyExchange().hasAnyRole(Role.acceptedNames(authorizeProperties.getEffectiveDefaultRoles()));
    }

    private void permitAll(ServerHttpSecurity.AuthorizeExchangeSpec exchanges, AuthorizeExchangeProperties.PathRule rule) {
        if (CollectionUtils.isEmpty(rule.methods())) {
            exchanges.pathMatchers(rule.path()).permitAll();
            return;
        }
        for (HttpMethod method : rule.methods()) {
            exchanges.pathMatchers(method, rule.path()).permitAll();
        }
    }

    private void requireRoles(ServerHttpSecurity.AuthorizeExchangeSpec exchanges, AuthorizeExchangeProperties.PathRule rule) {
        String[] acceptedRoleNames = Role.acceptedNames(rule.roles());
        if (CollectionUtils.isEmpty(rule.methods())) {
            exchanges.pathMatchers(rule.path()).hasAnyRole(acceptedRoleNames);
            return;
        }
        for (HttpMethod method : rule.methods()) {
            exchanges.pathMatchers(method, rule.path()).hasAnyRole(acceptedRoleNames);
        }
    }
}
