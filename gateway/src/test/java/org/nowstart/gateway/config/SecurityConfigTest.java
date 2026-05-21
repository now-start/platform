package org.nowstart.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nowstart.gateway.data.AuthorizeExchangeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@ActiveProfiles("test")
@SpringBootTest(classes = {
        SecurityConfig.class,
        GatewayAuthenticationEntryPoint.class,
        GatewayAuthorizationRules.class,
        GatewayUserDetailsServiceConfig.class,
        CustomAuthoritiesFilter.class,
        AuthorizeExchangeProperties.class
})
@ImportAutoConfiguration({
        ReactiveWebSecurityAutoConfiguration.class,
        WebFluxAutoConfiguration.class,
        RefreshAutoConfiguration.class
})
class SecurityConfigTest {

    private static final String BASIC_ADMIN_USERNAME = "basic-admin";
    private static final String BASIC_ADMIN_PASSWORD = "admin-secret";
    private static final String BASIC_USER_USERNAME = "basic-user";
    private static final String BASIC_USER_PASSWORD = "user-secret";

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
    @DisplayName("config refresh 운영 경로는 인증이 필요하다")
    void configRefreshPathShouldRequireAuthentication() {
        webTestClient.post()
                .uri("/internal/config-refresh")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("브라우저 HTML 요청은 SSO 로그인을 위해 로그인 페이지로 이동한다")
    void browserHtmlRequestShouldRedirectToLogin() {
        webTestClient.get()
                .uri("/config/other")
                .header("Accept", "text/html")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals("Location", "/login");
    }

    @Test
    @DisplayName("actuator 경로는 내부 관리 도구 접근을 위해 public으로 통과한다")
    void actuatorPathShouldBePublic() {
        webTestClient.get()
                .uri("/actuator/env")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("내장 Basic 인증은 config refresh 운영 경로를 통과한다")
    void basicAuthenticationShouldReachConfigRefreshPath() {
        webTestClient.post()
                .uri("/internal/config-refresh")
                .headers(headers -> headers.setBasicAuth(BASIC_ADMIN_USERNAME, BASIC_ADMIN_PASSWORD))
                .exchange()
                .expectStatus().isNotFound();
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
    @DisplayName("USERS Basic 인증은 명시된 사용자 경로를 통과한다")
    void usersBasicAuthenticationShouldReachUsersProtectedPath() {
        webTestClient.get()
                .uri("/admin/applications")
                .headers(headers -> headers.setBasicAuth(BASIC_USER_USERNAME, BASIC_USER_PASSWORD))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("USERS Basic 인증은 기본 관리자 경로를 통과하지 못한다")
    void usersBasicAuthenticationShouldNotReachDefaultAdminPath() {
        webTestClient.get()
                .uri("/config/other")
                .headers(headers -> headers.setBasicAuth(BASIC_USER_USERNAME, BASIC_USER_PASSWORD))
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
    }
}
