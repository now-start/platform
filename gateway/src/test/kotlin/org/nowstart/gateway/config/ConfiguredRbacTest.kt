package org.nowstart.gateway.config

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.nowstart.gateway.audit.AuditClientAddressResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.*
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@ActiveProfiles("test")
@SpringBootTest(
    classes =
        [
            SecurityConfig::class,
            GatewayAuthenticationEntryPoint::class,
            AuditClientAddressResolver::class,
            SecurityConfigTest.TestConfig::class,
        ],
    properties =
        [
            "gateway.rbac.rules[0].paths[0]=/reports/**",
            "gateway.rbac.rules[0].permit-all=true",
            "gateway.rbac.rules[1].paths[0]=/reports/private/**",
            "gateway.rbac.rules[1].role=REVIEWERS",
            "gateway.rbac.rules[2].paths[0]=/signed-in/**",
            "gateway.rbac.rules[2].authenticated=true",
            "gateway.rbac.groups-attribute=teams",
            "gateway.rbac.group-roles.review-team=REVIEWERS",
            "gateway.rbac.hierarchy.ADMINISTRATORS[0]=REVIEWERS",
        ],
)
@ImportAutoConfiguration(
    ReactiveWebSecurityAutoConfiguration::class,
    ReactiveUserDetailsServiceAutoConfiguration::class,
    WebFluxAutoConfiguration::class,
)
class ConfiguredRbacTest {
    @field:Autowired lateinit var context: ApplicationContext

    @field:MockitoBean lateinit var registrations: ReactiveClientRegistrationRepository

    private lateinit var client: WebTestClient

    @BeforeEach
    fun setUp() {
        client =
            WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .build()
    }

    @Test
    fun unmatchedUrlsRequireAdministratorAndOldPublicListIsReplaced() {
        client.get().uri("/unmatched").exchange().expectStatus().isUnauthorized()
        signedIn().get().uri("/unmatched").exchange().expectStatus().isForbidden()
        client
            .mutateWith(mockUser().roles("ADMINISTRATORS"))
            .get()
            .uri("/unmatched")
            .exchange()
            .expectStatus()
            .isNotFound()
        client.get().uri("/barosteel").exchange().expectStatus().isUnauthorized()
    }

    @Test
    fun authenticatedModeAcceptsLoginWithoutAnyApplicationRole() {
        client.get().uri("/signed-in/profile").exchange().expectStatus().isUnauthorized()
        signedIn().get().uri("/signed-in/profile").exchange().expectStatus().isNotFound()
    }

    @Test
    fun publicModeAllowsAnonymousButMoreSpecificRoleRuleTakesPrecedence() {
        client.get().uri("/reports/public").exchange().expectStatus().isNotFound()
        client.get().uri("/reports/private/a").exchange().expectStatus().isUnauthorized()
        signedIn().get().uri("/reports/private/a").exchange().expectStatus().isForbidden()
    }

    @Test
    fun configuredGroupsAttributeAndNewRoleAreUsedWithoutCodeChanges() {
        val reviewer =
            client.mutateWith(mockOAuth2Login().attributes { it["teams"] = listOf("ReViEw-TeAm") })
        reviewer.get().uri("/reports/private/a").exchange().expectStatus().isNotFound()
        reviewer.get().uri("/unmatched").exchange().expectStatus().isForbidden()
        client
            .mutateWith(mockOAuth2Login().attributes { it["groups"] = listOf("review-team") })
            .get()
            .uri("/reports/private/a")
            .exchange()
            .expectStatus()
            .isForbidden()
    }

    @Test
    fun configuredHierarchyAppliesToBasicAuthentication() {
        client
            .get()
            .uri("/reports/private/a")
            .headers { it.setBasicAuth("basic-admin", "admin-secret") }
            .exchange()
            .expectStatus()
            .isNotFound()
    }

    private fun signedIn(): WebTestClient =
        client.mutateWith(mockOAuth2Login().authorities(SimpleGrantedAuthority("OAUTH2_USER")))
}
