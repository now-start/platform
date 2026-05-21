package org.nowstart.gateway.config;

import java.time.Instant;
import java.util.List;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@ActiveProfiles("test")
@SpringBootTest(classes = {SecurityConfig.class, AuthorizeExchangeProperties.class})
@ImportAutoConfiguration({
        ReactiveWebSecurityAutoConfiguration.class,
        WebFluxAutoConfiguration.class,
        RefreshAutoConfiguration.class
})
class SecurityConfigTest {

    private static final String BASIC_USERNAME = "basic-user";
    private static final String BASIC_PASSWORD = "basic-secret";

    @Autowired
    private ApplicationContext context;

    private WebTestClient webTestClient;
    @MockitoBean
    private ReactiveClientRegistrationRepository clientRegistrationRepository;
    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

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
    @DisplayName("내장 Basic 인증은 config refresh 운영 경로를 통과한다")
    void basicAuthenticationShouldReachConfigRefreshPath() {
        webTestClient.post()
                .uri("/internal/config-refresh")
                .headers(headers -> headers.setBasicAuth(BASIC_USERNAME, BASIC_PASSWORD))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("내장 Basic 인증은 관리자 기본 경로를 통과한다")
    void basicAuthenticationShouldReachAdminProtectedPath() {
        webTestClient.get()
                .uri("/config/other")
                .headers(headers -> headers.setBasicAuth(BASIC_USERNAME, BASIC_PASSWORD))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("내장 Basic 인증은 USERS 경로도 통과한다")
    void basicAuthenticationShouldReachUsersProtectedPath() {
        webTestClient.get()
                .uri("/admin/applications")
                .headers(headers -> headers.setBasicAuth(BASIC_USERNAME, BASIC_PASSWORD))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("내장 Basic 인증 비밀번호가 틀리면 401을 반환한다")
    void invalidBasicAuthenticationShouldReturnUnauthorized() {
        webTestClient.get()
                .uri("/config/other")
                .headers(headers -> headers.setBasicAuth(BASIC_USERNAME, "wrong-secret"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("기존 JWT Authentication 흐름은 그대로 동작한다")
    void existingJwtAuthenticationShouldStillWork() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("jwt-user")
                .claim("groups", List.of("administrators"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        webTestClient.mutateWith(SecurityMockServerConfigurers.mockAuthentication(new JwtAuthenticationToken(jwt)))
                .get()
                .uri("/config/other")
                .exchange()
                .expectStatus().isNotFound();
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
