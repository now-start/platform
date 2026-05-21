package org.nowstart.gateway.config;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.nowstart.gateway.data.Role;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class CustomAuthoritiesFilter implements WebFilter {

    private static final String GROUPS_ATTRIBUTE = "groups";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String AUTHENTICATED_USER_HEADER = "X-Authenticated-User";
    private static final String AUTHENTICATED_ROLES_HEADER = "X-Authenticated-Roles";
    private static final String AUTHENTICATED_AUTH_TYPE_HEADER = "X-Authenticated-Auth-Type";
    private static final List<String> TRUSTED_AUTH_HEADERS = List.of(
            AUTHENTICATED_USER_HEADER,
            AUTHENTICATED_ROLES_HEADER,
            AUTHENTICATED_AUTH_TYPE_HEADER
    );

    @Override
    public @NonNull Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        ServerWebExchange sanitizedExchange = removeTrustedAuthHeaders(exchange);

        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> {
                    Authentication authentication = ctx.getAuthentication();
                    if (authentication == null) {
                        return chain.filter(sanitizedExchange);
                    }

                    logAuthentication(exchange.getRequest(), authentication);
                    if (authentication instanceof AnonymousAuthenticationToken) {
                        return chain.filter(sanitizedExchange);
                    }

                    Authentication mappedAuthentication = mapAuthentication(authentication);
                    ctx.setAuthentication(mappedAuthentication);

                    ServerWebExchange authenticatedExchange = addTrustedAuthHeaders(
                            sanitizedExchange,
                            mappedAuthentication
                    );
                    return chain.filter(authenticatedExchange)
                            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx)));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> chain.filter(sanitizedExchange)))
                .flatMap(mono -> mono);
    }

    private ServerWebExchange removeTrustedAuthHeaders(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> TRUSTED_AUTH_HEADERS.forEach(headers::remove))
                .build();
        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange addTrustedAuthHeaders(ServerWebExchange exchange, Authentication authentication) {
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    TRUSTED_AUTH_HEADERS.forEach(headers::remove);
                    headers.set(AUTHENTICATED_USER_HEADER, authentication.getName());
                    headers.set(AUTHENTICATED_AUTH_TYPE_HEADER, authentication.getClass().getSimpleName());

                    List<String> roles = normalizedRoles(authentication.getAuthorities());
                    if (!roles.isEmpty()) {
                        headers.set(AUTHENTICATED_ROLES_HEADER, String.join(",", roles));
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private List<String> normalizedRoles(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .distinct()
                .toList();
    }

    private void logAuthentication(ServerHttpRequest request, Authentication authentication) {
        log.info("[{}] SecurityContext found method={} path={} authType={} principal={} authorities={}",
                request.getId(),
                request.getMethod(),
                request.getPath(),
                authentication.getClass().getSimpleName(),
                authentication.getName(),
                authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList());
    }

    private Authentication mapAuthentication(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken auth) {
            return oAuth2Authentication(auth);
        }
        return authentication;
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
        return new OAuth2AuthenticationToken(principal, newAuthorities, auth.getAuthorizedClientRegistrationId());
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
                    .flatMap(role -> role.getAllIncluded().stream())
                    .map(Role::authority)
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authority -> byName.putIfAbsent(authority.getAuthority(), authority));
        }

        return List.copyOf(byName.values());
    }
}
