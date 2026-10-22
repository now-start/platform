package org.nowstart.gateway.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.server.authorization.AuthorizationContext
import reactor.core.publisher.Mono

class PathRbacAuthorizationManagerTest {
    @Test
    fun specificRulesWinForEveryRuleAndPathOrder() {
        val rules =
            listOf(
                RbacProperties.Rule(listOf("/service/**", "/other/**"), role = "ADMINISTRATORS"),
                RbacProperties.Rule(listOf("/service/public/**", "/free/**"), permitAll = true),
                RbacProperties.Rule(listOf("/service/public/profile/**"), authenticated = true),
                RbacProperties.Rule(
                    listOf("/service/public/profile/admin"),
                    role = "ADMINISTRATORS",
                ),
            )
        permutations(rules).forEach { order ->
            listOf(order, order.map { it.copy(paths = it.paths.reversed()) }).forEach { arranged ->
                val manager = manager(arranged)
                assertEquals(true, allowed(manager, "/service/public/news"))
                assertEquals(false, allowed(manager, "/service/secret"))
                assertEquals(false, allowed(manager, "/service/public/profile/me"))
                assertEquals(true, allowed(manager, "/service/public/profile/me", ""))
                assertEquals(false, allowed(manager, "/service/public/profile/admin", ""))
                assertEquals(
                    true,
                    allowed(manager, "/service/public/profile/admin", "ADMINISTRATORS"),
                )
                assertEquals(false, allowed(manager, "/unmatched", "USERS"))
                assertEquals(true, allowed(manager, "/unmatched", "ADMINISTRATORS"))
            }
        }
    }

    @Test
    fun equallySpecificConflictsAreDeniedRegardlessOfOrder() {
        listOf("/*/b", "/a/*").forEach { second ->
            val rules =
                listOf(
                    RbacProperties.Rule(listOf("/a/*"), permitAll = true),
                    RbacProperties.Rule(listOf(second), role = "ADMINISTRATORS"),
                )
            permutations(rules).forEach {
                assertEquals(false, allowed(manager(it), "/a/b"))
                assertEquals(false, allowed(manager(it), "/a/b", "ADMINISTRATORS"))
            }
        }
    }

    @Test
    fun equalPoliciesAndDisjointEqualSpecificityAreAccepted() {
        val manager =
            manager(
                listOf(
                    RbacProperties.Rule(listOf("/a/*", "/*/b"), permitAll = true),
                    RbacProperties.Rule(listOf("/c/*"), role = "ADMINISTRATORS"),
                ),
            )
        assertEquals(true, allowed(manager, "/a/b"))
        assertEquals(false, allowed(manager, "/c/d"))
        assertEquals(true, allowed(manager, "/c/d", "ADMINISTRATORS"))
    }

    private fun manager(rules: List<RbacProperties.Rule>) =
        PathRbacAuthorizationManager(
            RbacProperties(defaultRole = "ADMINISTRATORS", rules = rules, groupRoles = emptyMap()),
        )

    private fun allowed(
        manager: PathRbacAuthorizationManager,
        path: String,
        role: String? = null,
    ): Boolean {
        val authentication: Mono<Authentication> =
            if (role == null) Mono.empty()
            else
                Mono.just(
                    TestingAuthenticationToken(
                        "user",
                        "password",
                        if (role.isEmpty()) "OAUTH2_USER" else "ROLE_$role",
                    ),
                )
        val context =
            AuthorizationContext(MockServerWebExchange.from(MockServerHttpRequest.get(path)))
        return requireNotNull(manager.authorize(authentication, context).block()).isGranted
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.isEmpty()) listOf(emptyList())
        else items.flatMap { item -> permutations(items - item).map { listOf(item) + it } }
}
