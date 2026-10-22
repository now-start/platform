package org.nowstart.gateway.config

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.assertj.core.api.BDDAssertions.then
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.nowstart.gateway.audit.AuditClientAddressResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.audit.AuditEventRepository
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

@ActiveProfiles("test")
@SpringBootTest(
    classes =
        [
            SecurityConfig::class,
            GatewayAuthenticationEntryPoint::class,
            AuditClientAddressResolver::class,
            SecurityConfigTest.TestConfig::class,
        ],
)
@ImportAutoConfiguration(
    ReactiveWebSecurityAutoConfiguration::class,
    ReactiveUserDetailsServiceAutoConfiguration::class,
    WebFluxAutoConfiguration::class,
)
class SecurityConfigTest {

    private val BASIC_ADMIN_USERNAME: String = "basic-admin"
    private val BASIC_ADMIN_PASSWORD: String = "admin-secret"

    @field:Autowired private lateinit var context: ApplicationContext

    private lateinit var webTestClient: WebTestClient
    @field:MockitoBean
    private lateinit var clientRegistrationRepository: ReactiveClientRegistrationRepository

    @BeforeEach
    fun setUp() {
        webTestClient =
            WebTestClient.bindToApplicationContext(context)
                .apply(SecurityMockServerConfigurers.springSecurity())
                .configureClient()
                .build()
    }

    @Test
    @DisplayName("public 경로가 아닌 다른 /config/** 경로는 인증이 필요하다")
    fun genericAdminPathShouldStillRequireAuthentication() {
        // 준비
        val adminPath: String = "/config/other"

        // 실행 및 검증
        webTestClient.get().uri(adminPath).exchange().expectStatus().isUnauthorized()
    }

    @Test
    @DisplayName("브라우저 HTML 요청은 SSO 인증 시작 경로로 이동한다")
    fun browserHtmlRequestShouldRedirectToOAuth2Authorization() {
        webTestClient
            .get()
            .uri("/config/other")
            .header("Accept", "text/html")
            .exchange()
            .expectStatus()
            .isFound()
            .expectHeader()
            .valueEquals("Location", "/oauth2/authorization/nowstart")
    }

    @Test
    @DisplayName("사용하지 않는 기본 로그인 경로도 SSO 인증 시작 경로로 이동한다")
    fun unusedLoginPageShouldRedirectToOAuth2Authorization() {
        webTestClient
            .get()
            .uri("/login")
            .header("Accept", "text/html")
            .exchange()
            .expectStatus()
            .isFound()
            .expectHeader()
            .valueEquals("Location", "/oauth2/authorization/nowstart")
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "/actuator/env",
                "/nyang-nyang-bot/test",
                "/barosteel",
                "/barosteel/",
                "/barosteel/login",
                "/barosteel/admin/members",
            ],
    )
    @DisplayName("공개 경로는 인증 없이 통과한다")
    fun publicPathShouldBePublic(path: String) {
        webTestClient.get().uri(path).exchange().expectStatus().isNotFound()
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            ["/actuator/v3/api-docs", "/nyang-nyang-bot/v3/api-docs", "/barosteel/v3/api-docs"],
    )
    @DisplayName("공개 경로와 겹치는 구체적인 USERS 경로는 인증이 필요하다")
    fun specificUsersPathShouldTakePrecedenceOverPublicPath(path: String) {
        webTestClient.get().uri(path).exchange().expectStatus().isUnauthorized()
    }

    @Test
    @DisplayName("내장 Basic 인증은 관리자 기본 경로를 통과한다")
    fun basicAuthenticationShouldReachAdminProtectedPath() {
        webTestClient
            .get()
            .uri("/config/other")
            .headers(
                { headers ->
                    headers.setBasicAuth(BASIC_ADMIN_USERNAME, BASIC_ADMIN_PASSWORD)
                },
            )
            .exchange()
            .expectStatus()
            .isNotFound()
    }

    @Test
    @DisplayName("인증된 요청은 기존 Authorization과 Cookie를 유지한다")
    fun authenticatedRequestShouldPreserveCredentials() {
        val basicAuthorization: String =
            "Basic " +
                Base64.getEncoder()
                    .encodeToString(
                        (BASIC_ADMIN_USERNAME + ":" + BASIC_ADMIN_PASSWORD).toByteArray(
                            StandardCharsets.UTF_8,
                        ),
                    )

        webTestClient
            .get()
            .uri("/echo-headers")
            .headers(
                { headers ->
                    headers.setBasicAuth(BASIC_ADMIN_USERNAME, BASIC_ADMIN_PASSWORD)
                },
            )
            .cookie("SESSION", "test-session")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("\$.authorization")
            .isEqualTo(basicAuthorization)
            .jsonPath("\$.cookie")
            .isEqualTo("SESSION=test-session")
    }

    @Test
    @DisplayName("ADMINISTRATORS Basic 인증은 USERS 경로도 통과한다")
    fun basicAuthenticationShouldReachUsersProtectedPath() {
        webTestClient
            .get()
            .uri("/admin/applications")
            .headers(
                { headers ->
                    headers.setBasicAuth(BASIC_ADMIN_USERNAME, BASIC_ADMIN_PASSWORD)
                },
            )
            .exchange()
            .expectStatus()
            .isNotFound()
    }

    @Test
    @DisplayName("내장 Basic 인증 비밀번호가 틀리면 401을 반환한다")
    fun invalidBasicAuthenticationShouldReturnUnauthorized() {
        val before: java.time.Instant = java.time.Instant.now()
        webTestClient
            .get()
            .uri("/config/other")
            .headers({ headers -> headers.setBasicAuth(BASIC_ADMIN_USERNAME, "wrong-secret") })
            .exchange()
            .expectStatus()
            .isUnauthorized()
        then(
                context
                    .getBean(AuditEventRepository::class.java)
                    .find("anonymousUser", before, "AUTHENTICATION_FAILURE"),
            )
            .hasSize(1)
    }

    @Test
    fun recordsAuthenticationAndAuthorizationWithoutCredentials() {
        val before: java.time.Instant = java.time.Instant.now()
        webTestClient
            .get()
            .uri("/config/other")
            .headers(
                { headers ->
                    headers.setBasicAuth(BASIC_ADMIN_USERNAME, BASIC_ADMIN_PASSWORD)
                },
            )
            .exchange()
            .expectStatus()
            .isNotFound()
        val repository = context.getBean(AuditEventRepository::class.java)
        then(repository.find(BASIC_ADMIN_USERNAME, before, "AUTHENTICATION_SUCCESS")).hasSize(1)
        usersWebTestClient().get().uri("/config/other").exchange().expectStatus().isForbidden()
        val denied = repository.find(null, before, "AUTHORIZATION_FAILURE")
        then(denied).hasSize(1)
        then(denied.first().getData().toString())
            .doesNotContain(BASIC_ADMIN_PASSWORD, "Authorization")
    }

    @Test
    @DisplayName("Basic 사용자 생성은 Spring Boot 표준 자동 설정에 위임한다")
    fun basicUserShouldUseSpringBootAutoConfiguration() {
        then(context.containsBean("gatewayUserDetailsServiceConfig")).isFalse()
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "/admin",
                "/admin/",
                "/admin/applications",
                "/admin/wallboard",
                "/admin/journal",
                "/admin/external",
                "/admin/assets/app.js",
                "/admin/index.html",
                "/admin/swagger-ui/index.html",
                "/service/v3/api-docs",
                "/admin/v3/api-docs/swagger-config",
            ],
    )
    @DisplayName("USERS OAuth 인증은 명시된 사용자 경로를 통과한다")
    fun usersOAuthAuthenticationShouldReachUsersProtectedPath(path: String) {
        usersWebTestClient().get().uri(path).exchange().expectStatus().isNotFound()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/config/other", "/admin/settings"])
    @DisplayName("USERS OAuth 인증은 명시되지 않은 관리자 경로를 통과하지 못한다")
    fun usersOAuthAuthenticationShouldNotReachAdminPath(path: String) {
        usersWebTestClient().get().uri(path).exchange().expectStatus().isForbidden()
    }

    @Test
    @DisplayName("CORS가 비활성화되면 preflight 요청에도 CORS 허용 헤더가 추가되지 않는다")
    fun corsPreflightRequestShouldNotIncludeCorsHeadersWhenDisabled() {
        // 준비
        val path: String = "/config/other"
        val origin: String = "https://frontend.example.com"

        // 실행 및 검증
        webTestClient
            .options()
            .uri(path)
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "Authorization")
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectHeader()
            .doesNotExist("Access-Control-Allow-Origin")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/barosteel-other", "/barosteel-other/login", "/barosteeladmin"])
    @DisplayName("바로스틸과 접두어만 같은 경로는 공개하지 않는다")
    fun similarBarosteelPrefixShouldRemainProtected(path: String) {
        webTestClient.get().uri(path).exchange().expectStatus().isUnauthorized()
    }

    private fun usersWebTestClient(): WebTestClient {
        return webTestClient.mutateWith(
            SecurityMockServerConfigurers.mockOAuth2Login()
                .authorities(SimpleGrantedAuthority("ROLE_USERS")),
        )
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun httpExchangeRepository(): HttpExchangeRepository {
            return InMemoryHttpExchangeRepository()
        }

        @Bean
        fun auditEventRepository(): AuditEventRepository {
            return InMemoryAuditEventRepository()
        }

        @Bean
        fun echoHeadersRoute(): RouterFunction<ServerResponse> {
            return RouterFunctions.route()
                .GET(
                    "/echo-headers",
                    { request ->
                        ServerResponse.ok()
                            .bodyValue(
                                mapOf(
                                    "authorization" to headerOrEmpty(request, "Authorization"),
                                    "cookie" to headerOrEmpty(request, "Cookie"),
                                ),
                            )
                    },
                )
                .build()
        }

        private fun headerOrEmpty(request: ServerRequest, name: String): String {
            val value = request.headers().firstHeader(name)
            if (value == null) {
                return ""
            }
            return value
        }
    }
}
