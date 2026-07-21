package org.nowstart.gateway.config;

import static org.assertj.core.api.BDDAssertions.then;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

@ActiveProfiles("test")
@SpringBootTest(classes = {
        SecurityConfig.class,
        GatewayAuthenticationEntryPoint.class,
        SecurityConfigTest.TestConfig.class
})
@ImportAutoConfiguration({
        ReactiveWebSecurityAutoConfiguration.class,
        ReactiveUserDetailsServiceAutoConfiguration.class,
        WebFluxAutoConfiguration.class
})
class SecurityConfigTest {

    private static final String BASIC_ADMIN_USERNAME = "basic-admin";
    private static final String BASIC_ADMIN_PASSWORD = "admin-secret";

    @Autowired
    private ApplicationContext context;

    private WebTestClient webTestClient;
    @MockitoBean
    private ReactiveClientRegistrationRepository clientRegistrationRepository;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(SecurityMockServerConfigurers.springSecurity())
                .configureClient()
                .build();
    }

    @Test
    @DisplayName("public 경로가 아닌 다른 /config/** 경로는 인증이 필요하다")
    void genericAdminPathShouldStillRequireAuthentication() {
        // given
        String adminPath = "/config/other";

        // when & then
        webTestClient.get()
                .uri(adminPath)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("브라우저 HTML 요청은 SSO 인증 시작 경로로 이동한다")
    void browserHtmlRequestShouldRedirectToOAuth2Authorization() {
        webTestClient.get()
                .uri("/config/other")
                .header("Accept", "text/html")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals("Location", "/oauth2/authorization/nowstart");
    }

    @Test
    @DisplayName("사용하지 않는 기본 로그인 경로도 SSO 인증 시작 경로로 이동한다")
    void unusedLoginPageShouldRedirectToOAuth2Authorization() {
        webTestClient.get()
                .uri("/login")
                .header("Accept", "text/html")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals("Location", "/oauth2/authorization/nowstart");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/nyang-nyang-bot/test"})
    @DisplayName("공개 경로는 인증 없이 통과한다")
    void publicPathShouldBePublic(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isNotFound();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/v3/api-docs", "/nyang-nyang-bot/v3/api-docs"})
    @DisplayName("공개 경로와 겹치는 구체적인 USERS 경로는 인증이 필요하다")
    void specificUsersPathShouldTakePrecedenceOverPublicPath(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("내장 Basic 인증은 관리자 기본 경로를 통과한다")
    void basicAuthenticationShouldReachAdminProtectedPath() {
        webTestClient.get()
                .uri("/config/other")
                .headers(headers -> headers.setBasicAuth(BASIC_ADMIN_USERNAME, BASIC_ADMIN_PASSWORD))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("인증된 요청은 기존 Authorization과 Cookie를 유지한다")
    void authenticatedRequestShouldPreserveCredentials() {
        String basicAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                (BASIC_ADMIN_USERNAME + ":" + BASIC_ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8)
        );

        webTestClient.get()
                .uri("/echo-headers")
                .headers(headers -> headers.setBasicAuth(BASIC_ADMIN_USERNAME, BASIC_ADMIN_PASSWORD))
                .cookie("SESSION", "test-session")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authorization").isEqualTo(basicAuthorization)
                .jsonPath("$.cookie").isEqualTo("SESSION=test-session");
    }

    @Test
    @DisplayName("ADMINISTRATORS Basic 인증은 USERS 경로도 통과한다")
    void basicAuthenticationShouldReachUsersProtectedPath() {
        webTestClient.get()
                .uri("/admin/applications")
                .headers(headers -> headers.setBasicAuth(BASIC_ADMIN_USERNAME, BASIC_ADMIN_PASSWORD))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("내장 Basic 인증 비밀번호가 틀리면 401을 반환한다")
    void invalidBasicAuthenticationShouldReturnUnauthorized() {
        webTestClient.get()
                .uri("/config/other")
                .headers(headers -> headers.setBasicAuth(BASIC_ADMIN_USERNAME, "wrong-secret"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Basic 사용자 생성은 Spring Boot 표준 자동 설정에 위임한다")
    void basicUserShouldUseSpringBootAutoConfiguration() {
        then(context.containsBean("gatewayUserDetailsServiceConfig")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
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
            "/admin/v3/api-docs/swagger-config"
    })
    @DisplayName("USERS OAuth 인증은 명시된 사용자 경로를 통과한다")
    void usersOAuthAuthenticationShouldReachUsersProtectedPath(String path) {
        usersWebTestClient().get()
                .uri(path)
                .exchange()
                .expectStatus().isNotFound();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/config/other", "/admin/settings"})
    @DisplayName("USERS OAuth 인증은 명시되지 않은 관리자 경로를 통과하지 못한다")
    void usersOAuthAuthenticationShouldNotReachAdminPath(String path) {
        usersWebTestClient().get()
                .uri(path)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("CORS가 비활성화되면 preflight 요청에도 CORS 허용 헤더가 추가되지 않는다")
    void corsPreflightRequestShouldNotIncludeCorsHeadersWhenDisabled() {
        // given
        String path = "/config/other";
        String origin = "https://frontend.example.com";

        // when & then
        webTestClient.options()
                .uri(path)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    private WebTestClient usersWebTestClient() {
        return webTestClient.mutateWith(SecurityMockServerConfigurers.mockOAuth2Login()
                .authorities(new SimpleGrantedAuthority("ROLE_USERS")));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public HttpExchangeRepository httpExchangeRepository() {
            return new InMemoryHttpExchangeRepository();
        }

        @Bean
        public AuditEventRepository auditEventRepository() {
            return new InMemoryAuditEventRepository();
        }

        @Bean
        public RouterFunction<ServerResponse> echoHeadersRoute() {
            return RouterFunctions.route()
                    .GET("/echo-headers", request -> ServerResponse.ok().bodyValue(Map.of(
                            "authorization", headerOrEmpty(request, "Authorization"),
                            "cookie", headerOrEmpty(request, "Cookie")
                    )))
                    .build();
        }

        private static String headerOrEmpty(ServerRequest request, String name) {
            String value = request.headers().firstHeader(name);
            if (value == null) {
                return "";
            }
            return value;
        }
    }
}
