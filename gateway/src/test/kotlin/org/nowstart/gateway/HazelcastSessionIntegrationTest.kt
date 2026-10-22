package org.nowstart.gateway

import com.hazelcast.core.HazelcastInstance
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.BDDAssertions.then
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.nowstart.gateway.session.HazelcastReactiveSessionRepository
import org.springframework.boot.WebApplicationType
import org.springframework.boot.actuate.audit.AuditEvent
import org.springframework.boot.actuate.audit.AuditEventRepository
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository
import org.springframework.session.ReactiveSessionRepository
import org.springframework.test.web.reactive.server.FluxExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

@DisplayName("Gateway Hazelcast 세션 통합")
class HazelcastSessionIntegrationTest {

    @Test
    @DisplayName("한 Gateway에서 생성한 인증 세션을 다른 Gateway에서 사용할 수 있다")
    fun authenticatedSessionCreatedByOneGatewayCanBeUsedByAnotherGateway() {
        val clusterName = "gateway-session-test-" + UUID.randomUUID()
        startGateway(clusterName, "127.0.0.1").use { gatewayA ->
            val memberA = gatewayA.getBean(HazelcastInstance::class.java)
            startGateway(clusterName, "127.0.0.1:" + memberA.cluster.localMember.address.port)
                .use { gatewayB ->
                    val memberB = gatewayB.getBean(HazelcastInstance::class.java)
                    await().atMost(Duration.ofSeconds(30)).untilAsserted {
                        assertThat(memberB.cluster.members).hasSize(2)
                    }
                    val repositoryA: Any = gatewayA.getBean(ReactiveSessionRepository::class.java)
                    val repositoryB: Any = gatewayB.getBean(ReactiveSessionRepository::class.java)
                    then(repositoryA).isInstanceOf(HazelcastReactiveSessionRepository::class.java)
                    then(repositoryB).isInstanceOf(HazelcastReactiveSessionRepository::class.java)
                    then(repositoryB).isNotSameAs(repositoryA)
                    then(gatewayA.getBean(ServerOAuth2AuthorizedClientRepository::class.java))
                        .isInstanceOf(WebSessionServerOAuth2AuthorizedClientRepository::class.java)
                    then(gatewayB.getBean(ServerOAuth2AuthorizedClientRepository::class.java))
                        .isInstanceOf(WebSessionServerOAuth2AuthorizedClientRepository::class.java)

                    val clientA: WebTestClient = webTestClient(gatewayA)
                    val clientB: WebTestClient = webTestClient(gatewayB)
                    clientA
                        .get()
                        .uri("/actuator")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("\$._links.health")
                        .exists()
                        .jsonPath("\$._links.env")
                        .exists()
                        .jsonPath("\$._links.sessions")
                        .exists()
                    clientB
                        .get()
                        .uri("/admin/session-probe.json")
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange()
                        .expectStatus()
                        .isUnauthorized()

                    val loginResult: FluxExchangeResult<Void> =
                        clientA
                            .post()
                            .uri("/actuator/session-probe")
                            .exchange()
                            .expectStatus()
                            .isNoContent()
                            .expectCookie()
                            .exists("SESSION")
                            .returnResult(Void::class.java)
                    val sessionId: String =
                        requireNotNull(loginResult.getResponseCookies().getFirst("SESSION"))
                            .getValue()

                    gatewayA
                        .getBean(AuditEventRepository::class.java)
                        .add(
                            AuditEvent(
                                PRINCIPAL_NAME,
                                "AUTHENTICATION_SUCCESS",
                                mapOf(
                                    "details" to
                                        mapOf(
                                            "remoteAddress" to "127.0.0.1",
                                            "sessionId" to sessionId,
                                        ),
                                ),
                            ),
                        )
                    memberA.lifecycleService.terminate()
                    await().atMost(Duration.ofSeconds(30)).untilAsserted {
                        assertThat(memberB.cluster.members).hasSize(1)
                    }
                    clientB
                        .get()
                        .uri("/actuator/auditevents?principal=" + PRINCIPAL_NAME)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("\$.events[0].principal")
                        .isEqualTo(PRINCIPAL_NAME)
                        .jsonPath("\$.events[0].data.details.remoteAddress")
                        .isEqualTo("127.0.0.1")
                        .jsonPath("\$.events[0].data.details.sessionId")
                        .isEqualTo(sessionId)

                    clientB
                        .get()
                        .uri("/admin/session-probe.json")
                        .accept(MediaType.APPLICATION_JSON)
                        .cookie("SESSION", sessionId)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("\$.principalName")
                        .isEqualTo(PRINCIPAL_NAME)
                        .jsonPath("\$.registrationId")
                        .isEqualTo(REGISTRATION_ID)
                        .jsonPath("\$.accessToken")
                        .isEqualTo(ACCESS_TOKEN)
                }
        }
    }

    private fun startGateway(clusterName: String, members: String): ConfigurableApplicationContext {
        return SpringApplicationBuilder(
                GatewayServiceApplication::class.java,
                SessionProbeConfiguration::class.java,
            )
            .profiles("test")
            .web(WebApplicationType.REACTIVE)
            .run(
                "--server.port=0",
                "--spring.cloud.config.enabled=false",
                "--gateway.cluster.name=" + clusterName,
                "--gateway.cluster.members=" + members,
                "--gateway.cluster.interfaces=127.0.0.1",
                "--gateway.cluster.port=0",
                "--spring.session.timeout=5m",
                "--management.endpoints.web.exposure.include=*",
            )
    }

    private fun webTestClient(context: ConfigurableApplicationContext): WebTestClient {
        val port: Int =
            requireNotNull(
                context
                    .getEnvironment()
                    .getProperty("local.server.port", Int::class.javaObjectType),
            )
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class SessionProbeConfiguration {

        @Bean
        fun sessionProbeRoute(
            registrations: ReactiveClientRegistrationRepository,
            authorizedClients: ServerOAuth2AuthorizedClientRepository,
        ): RouterFunction<ServerResponse> {
            return RouterFunctions.route()
                .POST(
                    "/actuator/session-probe",
                    { request ->
                        request
                            .session()
                            .flatMap(
                                { session ->
                                    val authority = SimpleGrantedAuthority("ROLE_USERS")
                                    val principal =
                                        DefaultOAuth2User(
                                            listOf(authority),
                                            mapOf("sub" to PRINCIPAL_NAME),
                                            "sub",
                                        )
                                    val authentication =
                                        OAuth2AuthenticationToken(
                                            principal,
                                            listOf(authority),
                                            REGISTRATION_ID,
                                        )
                                    session
                                        .getAttributes()
                                        .put(
                                            WebSessionServerSecurityContextRepository
                                                .DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME,
                                            SecurityContextImpl(authentication),
                                        )
                                    registrations
                                        .findByRegistrationId(REGISTRATION_ID)
                                        .switchIfEmpty(
                                            Mono.error(
                                                IllegalStateException(
                                                    "OAuth2 client registration is missing",
                                                ),
                                            ),
                                        )
                                        .flatMap(
                                            { registration ->
                                                authorizedClients.saveAuthorizedClient(
                                                    authorizedClient(registration),
                                                    authentication,
                                                    request.exchange(),
                                                )
                                            },
                                        )
                                        .then(ServerResponse.noContent().build())
                                },
                            )
                    },
                )
                .GET(
                    "/admin/session-probe.json",
                    { request ->
                        request
                            .principal()
                            .cast(OAuth2AuthenticationToken::class.java)
                            .flatMap(
                                { authentication ->
                                    authorizedClients
                                        .loadAuthorizedClient<OAuth2AuthorizedClient>(
                                            REGISTRATION_ID,
                                            authentication,
                                            request.exchange(),
                                        )
                                        .flatMap(
                                            { client ->
                                                ServerResponse.ok()
                                                    .bodyValue(
                                                        mapOf(
                                                            "principalName" to
                                                                authentication.getName(),
                                                            "registrationId" to
                                                                client
                                                                    .getClientRegistration()
                                                                    .getRegistrationId(),
                                                            "accessToken" to
                                                                client
                                                                    .getAccessToken()
                                                                    .getTokenValue(),
                                                        ),
                                                    )
                                            },
                                        )
                                },
                            )
                    },
                )
                .build()
        }

        private fun authorizedClient(registration: ClientRegistration): OAuth2AuthorizedClient {
            val issuedAt: Instant = Instant.now()
            val accessToken =
                OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    ACCESS_TOKEN,
                    issuedAt,
                    issuedAt.plusSeconds(300),
                )
            return OAuth2AuthorizedClient(registration, PRINCIPAL_NAME, accessToken)
        }
    }

    companion object {
        private val REGISTRATION_ID: String = "test"
        private val PRINCIPAL_NAME: String = "hazelcast-session-user"
        private val ACCESS_TOKEN: String = "hazelcast-session-access-token"
    }
}
