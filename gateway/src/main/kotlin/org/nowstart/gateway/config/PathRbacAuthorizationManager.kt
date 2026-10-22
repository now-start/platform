package org.nowstart.gateway.config

import org.springframework.security.authorization.AuthenticatedReactiveAuthorizationManager
import org.springframework.security.authorization.AuthorityReactiveAuthorizationManager
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.authorization.ReactiveAuthorizationManager
import org.springframework.security.core.Authentication
import org.springframework.security.web.server.authorization.AuthorizationContext
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono

class PathRbacAuthorizationManager(rbac: RbacProperties) :
    ReactiveAuthorizationManager<AuthorizationContext> {
    private data class Policy(
        val role: String?,
        val permitAll: Boolean,
        val authenticated: Boolean,
    ) {
        val manager: ReactiveAuthorizationManager<AuthorizationContext> =
            when {
                permitAll ->
                    ReactiveAuthorizationManager { _, _ -> Mono.just(AuthorizationDecision(true)) }
                authenticated -> AuthenticatedReactiveAuthorizationManager.authenticated()
                else -> AuthorityReactiveAuthorizationManager.hasRole(requireNotNull(role))
            }
    }

    private data class Entry(val pattern: PathPattern, val policy: Policy)

    private val entries =
        rbac.rules
            .flatMap { rule ->
                val policy = Policy(rule.role, rule.permitAll, rule.authenticated)
                rule.paths.map { Entry(PathPatternParser.defaultInstance.parse(it), policy) }
            }
            .sortedWith { left, right ->
                PathPattern.SPECIFICITY_COMPARATOR.compare(left.pattern, right.pattern)
            }

    private val fallback =
        AuthorityReactiveAuthorizationManager.hasRole<AuthorizationContext>(rbac.defaultRole)

    override fun authorize(
        authentication: Mono<Authentication>,
        context: AuthorizationContext,
    ): Mono<AuthorizationResult> {
        val path = context.exchange.request.path.pathWithinApplication()
        val matches = entries.filter { it.pattern.matches(path) }
        val best = matches.firstOrNull() ?: return fallback.authorize(authentication, context)
        // 같은 구체성의 경로가 서로 다른 정책을 요구하면 설정 순서로 선택하지 않고 거부한다.
        val conflicting =
            matches
                .takeWhile {
                    PathPattern.SPECIFICITY_COMPARATOR.compare(best.pattern, it.pattern) == 0
                }
                .any { it.policy != best.policy }
        return if (conflicting) Mono.just(AuthorizationDecision(false))
        else best.policy.manager.authorize(authentication, context)
    }
}
