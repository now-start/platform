package org.nowstart.gateway.config

import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class GatewayAuthenticationEntryPoint : ServerAuthenticationEntryPoint {
    override fun commence(exchange: ServerWebExchange, ex: AuthenticationException): Mono<Void> {
        if (exchange.request.headers.accept.any { it.isCompatibleWith(MediaType.TEXT_HTML) }) {
            exchange.response.statusCode = HttpStatus.FOUND
            exchange.response.headers.location = URI.create("/oauth2/authorization/nowstart")
        } else {
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            exchange.response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"gateway\"")
        }
        return exchange.response.setComplete()
    }
}
