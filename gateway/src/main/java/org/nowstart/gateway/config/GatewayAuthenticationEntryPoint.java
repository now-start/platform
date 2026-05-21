package org.nowstart.gateway.config;

import java.net.URI;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private static final String INTERNAL_PATH_PREFIX = "/internal/";
    private static final String BASIC_REALM = "Basic realm=\"gateway\"";
    private static final URI LOGIN_LOCATION = URI.create("/oauth2/authorization/nowstart");

    @Override
    public @NonNull Mono<Void> commence(
            @NonNull ServerWebExchange exchange,
            @NonNull AuthenticationException ex
    ) {
        if (isBrowserNavigation(exchange.getRequest())) {
            exchange.getResponse().setStatusCode(HttpStatus.FOUND);
            exchange.getResponse().getHeaders().setLocation(LOGIN_LOCATION);
            return exchange.getResponse().setComplete();
        }

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, BASIC_REALM);
        return exchange.getResponse().setComplete();
    }

    private boolean isBrowserNavigation(ServerHttpRequest request) {
        if (request.getPath().pathWithinApplication().value().startsWith(INTERNAL_PATH_PREFIX)) {
            return false;
        }
        return request.getHeaders().getAccept().stream()
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_HTML));
    }
}
