package org.nowstart.gateway.config

import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyAuthoritiesMapper
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class CustomAuthoritiesFilter(roleHierarchy: RoleHierarchy, private val rbac: RbacProperties) :
    WebFilter {
    private val authoritiesMapper = RoleHierarchyAuthoritiesMapper(roleHierarchy)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        ReactiveSecurityContextHolder.getContext()
            .map { ctx ->
                val authentication = ctx.authentication
                if (authentication == null || authentication is AnonymousAuthenticationToken) {
                    chain.filter(exchange)
                } else {
                    ctx.authentication = mapAuthentication(authentication)
                    chain
                        .filter(exchange)
                        .contextWrite(
                            ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx)),
                        )
                }
            }
            .switchIfEmpty(Mono.fromSupplier { chain.filter(exchange) })
            .flatMap { it }

    private fun mapAuthentication(authentication: Authentication): Authentication =
        when {
            authentication is OAuth2AuthenticationToken -> {
                val principal = authentication.principal
                val groups = principal.attributes[rbac.groupsAttribute] as? Collection<*>
                val authorities =
                    authentication.authorities.associateByTo(linkedMapOf()) { it.authority }
                groups
                    ?.filterNotNull()
                    ?.mapNotNull { rbac.authorityForGroup(it.toString()) }
                    ?.forEach {
                        authorities.putIfAbsent(it, SimpleGrantedAuthority(it))
                    }
                OAuth2AuthenticationToken(
                        principal,
                        expandAuthorities(authorities.values),
                        authentication.authorizedClientRegistrationId,
                    )
                    .apply { details = authentication.details }
            }
            authentication is UsernamePasswordAuthenticationToken &&
                authentication.isAuthenticated ->
                UsernamePasswordAuthenticationToken.authenticated(
                        requireNotNull(authentication.principal),
                        authentication.credentials,
                        expandAuthorities(authentication.authorities),
                    )
                    .apply { details = authentication.details }
            else -> authentication
        }

    private fun expandAuthorities(
        authorities: Collection<GrantedAuthority>,
    ): List<GrantedAuthority> = authoritiesMapper.mapAuthorities(authorities).toList()
}
