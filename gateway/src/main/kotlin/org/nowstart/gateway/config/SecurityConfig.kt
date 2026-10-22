package org.nowstart.gateway.config

import java.security.Principal
import org.nowstart.gateway.audit.AuditClientAddressResolver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.audit.AuditEvent
import org.springframework.boot.actuate.audit.AuditEventRepository
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.HttpBasicServerAuthenticationEntryPoint
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.WebFilterChainServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(RbacProperties::class)
class SecurityConfig(
    private val authenticationEntryPoint: GatewayAuthenticationEntryPoint,
    @param:Value("\${spring.security.user.password}") basicPassword: String?,
    private val rbac: RbacProperties,
    private val clientAddressResolver: AuditClientAddressResolver,
) {
    init {
        check(basicPassword != null && BCRYPT_PASSWORD_PATTERN.matches(basicPassword)) {
            "spring.security.user.password must be an explicit BCrypt hash"
        }
    }

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        roleHierarchy: RoleHierarchy,
        audit: AuditEventRepository,
    ): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .headers { headers ->
                headers
                    .frameOptions { it.disable() }
                    .contentSecurityPolicy {
                        it.policyDirectives("upgrade-insecure-requests; frame-ancestors 'self'")
                    }
            }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler {
                    exchange,
                    denied ->
                    exchange
                        .getPrincipal<Principal>()
                        .map { it.name }
                        .defaultIfEmpty("anonymousUser")
                        .flatMap {
                            recordAudit(
                                audit,
                                exchange,
                                it,
                                "AUTHORIZATION_FAILURE",
                                clientAddressResolver,
                            )
                        }
                        .then(
                            HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN)
                                .handle(exchange, denied),
                        )
                }
            }
            .authorizeExchange { exchanges ->
                exchanges.anyExchange().access(PathRbacAuthorizationManager(rbac))
            }
            .httpBasic { basic ->
                basic
                    .authenticationSuccessHandler { exchange, authentication ->
                        recordAudit(
                                audit,
                                exchange.exchange,
                                authentication.name,
                                "AUTHENTICATION_SUCCESS",
                                clientAddressResolver,
                            )
                            .then(
                                WebFilterChainServerAuthenticationSuccessHandler()
                                    .onAuthenticationSuccess(exchange, authentication),
                            )
                    }
                    .authenticationFailureHandler { exchange, failure ->
                        recordAudit(
                                audit,
                                exchange.exchange,
                                "anonymousUser",
                                "AUTHENTICATION_FAILURE",
                                clientAddressResolver,
                            )
                            .then(
                                HttpBasicServerAuthenticationEntryPoint()
                                    .commence(exchange.exchange, failure),
                            )
                    }
            }
            .oauth2Login { oauth2 ->
                oauth2
                    .authenticationSuccessHandler { exchange, authentication ->
                        recordAudit(
                                audit,
                                exchange.exchange,
                                authentication.name,
                                "AUTHENTICATION_SUCCESS",
                                clientAddressResolver,
                            )
                            .then(
                                oAuth2SuccessHandler()
                                    .onAuthenticationSuccess(exchange, authentication),
                            )
                    }
                    .authenticationFailureHandler { exchange, failure ->
                        recordAudit(
                                audit,
                                exchange.exchange,
                                "anonymousUser",
                                "AUTHENTICATION_FAILURE",
                                clientAddressResolver,
                            )
                            .then(
                                RedirectServerAuthenticationFailureHandler("/login?error")
                                    .onAuthenticationFailure(exchange, failure),
                            )
                    }
            }
            .addFilterAfter(
                CustomAuthoritiesFilter(roleHierarchy, rbac),
                SecurityWebFiltersOrder.AUTHENTICATION,
            )
            .build()

    @Bean
    fun roleHierarchy(): RoleHierarchy =
        RoleHierarchyImpl.fromHierarchy(
            rbac.hierarchy
                .flatMap { (role, implied) -> implied.map { "ROLE_$role > ROLE_$it" } }
                .joinToString("\n"),
        )

    @Bean
    fun authorizedClientRepository(): ServerOAuth2AuthorizedClientRepository =
        WebSessionServerOAuth2AuthorizedClientRepository()

    private fun oAuth2SuccessHandler() =
        RedirectServerAuthenticationSuccessHandler("/admin").apply {
            setRequestCache(WebSessionServerRequestCache())
        }

    companion object {
        private val log = LoggerFactory.getLogger(SecurityConfig::class.java)
        private val BCRYPT_PASSWORD_PATTERN =
            Regex("\\{bcrypt\\}\\$2[aby]\\$(?:0[4-9]|[12]\\d|3[01])\\$[./0-9A-Za-z]{53}")

        fun recordAudit(
            repository: AuditEventRepository,
            exchange: ServerWebExchange,
            principal: String,
            type: String,
            clientAddressResolver: AuditClientAddressResolver = AuditClientAddressResolver(""),
        ): Mono<Void> =
            exchange.session
                .flatMap { session ->
                    val details = linkedMapOf<String, Any>()
                    clientAddressResolver.resolve(exchange.request)?.let {
                        details["remoteAddress"] = it
                    }
                    // 감사 때문에 빈 세션을 시작하거나 쿠키를 만들지 않는다.
                    if (session.isStarted) details["sessionId"] = session.id
                    val event = AuditEvent(principal, type, mapOf("details" to details))
                    Mono.fromRunnable<Void> { repository.add(event) }
                        .subscribeOn(Schedulers.boundedElastic())
                        .then()
                }
                .onErrorResume { failure ->
                    // 예외 메시지에 연결 정보나 개인정보가 포함될 수 있어 타입만 기록한다.
                    log.warn("Gateway audit write failed: type={}", failure.javaClass.simpleName)
                    Mono.empty()
                }
    }
}
