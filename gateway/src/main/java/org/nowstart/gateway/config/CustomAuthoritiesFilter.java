package org.nowstart.gateway.config;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.nowstart.gateway.data.Role;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyAuthoritiesMapper;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public class CustomAuthoritiesFilter implements WebFilter {

    private static final String GROUPS_ATTRIBUTE = "groups";
    private final RoleHierarchyAuthoritiesMapper authoritiesMapper;

    public CustomAuthoritiesFilter(RoleHierarchy roleHierarchy) {
        this.authoritiesMapper = new RoleHierarchyAuthoritiesMapper(roleHierarchy);
    }

    @Override
    public @NonNull Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> {
                    Authentication authentication = ctx.getAuthentication();
                    if (authentication == null) {
                        return chain.filter(exchange);
                    }

                    if (authentication instanceof AnonymousAuthenticationToken) {
                        return chain.filter(exchange);
                    }

                    Authentication mappedAuthentication = mapAuthentication(authentication);
                    ctx.setAuthentication(mappedAuthentication);

                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx)));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> chain.filter(exchange)))
                .flatMap(mono -> mono);
    }

    private Authentication mapAuthentication(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken auth) {
            return oAuth2Authentication(auth);
        }
        if (authentication instanceof UsernamePasswordAuthenticationToken auth && auth.isAuthenticated()) {
            return usernamePasswordAuthentication(auth);
        }
        return authentication;
    }

    private Authentication usernamePasswordAuthentication(UsernamePasswordAuthenticationToken auth) {
        var mappedAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                auth.getPrincipal(),
                auth.getCredentials(),
                expandAuthorities(auth.getAuthorities())
        );
        mappedAuthentication.setDetails(auth.getDetails());
        return mappedAuthentication;
    }

    private Authentication oAuth2Authentication(OAuth2AuthenticationToken auth) {
        OAuth2User principal = auth.getPrincipal();
        Object groupsObj = Objects.requireNonNull(principal).getAttributes().get(GROUPS_ATTRIBUTE);
        Collection<String> groupNames = null;

        if (groupsObj instanceof Collection<?> groups) {
            groupNames = groups.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }

        List<GrantedAuthority> newAuthorities = mapGroupsToAuthorities(groupNames, auth.getAuthorities());
        var mappedAuthentication = new OAuth2AuthenticationToken(
                principal,
                expandAuthorities(newAuthorities),
                auth.getAuthorizedClientRegistrationId()
        );
        mappedAuthentication.setDetails(auth.getDetails());
        return mappedAuthentication;
    }

    private List<GrantedAuthority> mapGroupsToAuthorities(Collection<String> groups, Collection<GrantedAuthority> existingAuthorities) {
        Map<String, GrantedAuthority> byName = new LinkedHashMap<>();
        for (GrantedAuthority authority : existingAuthorities) {
            byName.put(authority.getAuthority(), authority);
        }

        if (groups != null) {
            groups.stream()
                    .map(Role::from)
                    .filter(Objects::nonNull)
                    .map(Role::authority)
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authority -> byName.putIfAbsent(authority.getAuthority(), authority));
        }

        return List.copyOf(byName.values());
    }

    private List<GrantedAuthority> expandAuthorities(Collection<? extends GrantedAuthority> authorities) {
        return List.copyOf(authoritiesMapper.mapAuthorities(authorities));
    }
}
