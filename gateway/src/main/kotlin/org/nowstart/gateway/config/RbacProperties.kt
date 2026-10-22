package org.nowstart.gateway.config

import java.util.Locale
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher

@ConfigurationProperties("gateway.rbac")
data class RbacProperties(
    val defaultRole: String,
    val rules: List<Rule>,
    val groupRoles: Map<String, String>,
    val hierarchy: Map<String, List<String>> = emptyMap(),
    val groupsAttribute: String = "groups",
) {
    init {
        validateRole(defaultRole)
        require(groupsAttribute.isNotBlank()) { "gateway.rbac.groups-attribute must not be blank" }
        groupRoles.forEach { (group, role) ->
            require(group.isNotBlank()) { "RBAC group must not be blank" }
            validateRole(role)
        }
        require(
            groupRoles.keys.map { it.uppercase(Locale.ROOT) }.distinct().size == groupRoles.size,
        ) {
            "RBAC groups must be unique ignoring case"
        }
        hierarchy.forEach { (role, implied) ->
            validateRole(role)
            implied.forEach(::validateRole)
        }
    }

    private val normalizedGroupRoles = groupRoles.mapKeys { it.key.uppercase(Locale.ROOT) }

    fun authorityForGroup(group: String): String? =
        normalizedGroupRoles[group.uppercase(Locale.ROOT)]?.let { "ROLE_$it" }

    data class Rule(
        val paths: List<String>,
        val role: String? = null,
        val permitAll: Boolean = false,
        val authenticated: Boolean = false,
    ) {
        init {
            require(paths.isNotEmpty() && paths.all { it.startsWith("/") }) {
                "RBAC rule requires absolute paths"
            }
            require(listOf(permitAll, authenticated, role != null).count { it } == 1) {
                "RBAC rule requires exactly one of role, authenticated or permit-all"
            }
            role?.let(::validateRole)
            paths.forEach { PathPatternParserServerWebExchangeMatcher(it) }
        }
    }

    companion object {
        private fun validateRole(role: String) {
            require(role.matches(Regex("[A-Z][A-Z0-9_]*")) && !role.startsWith("ROLE_")) {
                "RBAC roles must be uppercase names without the ROLE_ prefix"
            }
        }
    }
}
