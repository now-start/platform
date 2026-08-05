package org.nowstart.gateway;

import static org.assertj.core.api.BDDAssertions.then;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

@Testcontainers
@DisplayName("Gateway Redis 세션 통합")
class RedisSessionIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final String SESSION_NAMESPACE = "nowstart:gateway:test:session";
    private static final String REGISTRATION_ID = "test";
    private static final String PRINCIPAL_NAME = "redis-session-user";
    private static final String ACCESS_TOKEN = "redis-session-access-token";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")
    ).withExposedPorts(REDIS_PORT);

    @Test
    @DisplayName("한 Gateway에서 생성한 인증 세션을 다른 Gateway에서 사용할 수 있다")
    void authenticatedSessionCreatedByOneGatewayCanBeUsedByAnotherGateway() {
        try (ConfigurableApplicationContext gatewayA = startGateway();
             ConfigurableApplicationContext gatewayB = startGateway()) {
            Object repositoryA = gatewayA.getBean(ReactiveSessionRepository.class);
            Object repositoryB = gatewayB.getBean(ReactiveSessionRepository.class);
            then(repositoryA).isInstanceOf(ReactiveRedisSessionRepository.class);
            then(repositoryB).isInstanceOf(ReactiveRedisSessionRepository.class);
            then(repositoryB).isNotSameAs(repositoryA);
            then(gatewayA.getBean(ServerOAuth2AuthorizedClientRepository.class))
                    .isInstanceOf(WebSessionServerOAuth2AuthorizedClientRepository.class);
            then(gatewayB.getBean(ServerOAuth2AuthorizedClientRepository.class))
                    .isInstanceOf(WebSessionServerOAuth2AuthorizedClientRepository.class);

            WebTestClient clientA = webTestClient(gatewayA);
            WebTestClient clientB = webTestClient(gatewayB);
            clientA.get()
                    .uri("/actuator")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$._links.health").exists()
                    .jsonPath("$._links.env").doesNotExist()
                    .jsonPath("$._links.sessions").doesNotExist();
            clientB.get()
                    .uri("/admin/session-probe.json")
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange()
                    .expectStatus().isUnauthorized();

            FluxExchangeResult<Void> loginResult = clientA.post()
                    .uri("/actuator/session-probe")
                    .exchange()
                    .expectStatus().isNoContent()
                    .expectCookie().exists("SESSION")
                    .returnResult(Void.class);
            String sessionId = Objects.requireNonNull(
                    loginResult.getResponseCookies().getFirst("SESSION")
            ).getValue();

            clientB.get()
                    .uri("/admin/session-probe.json")
                    .accept(MediaType.APPLICATION_JSON)
                    .cookie("SESSION", sessionId)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.principalName").isEqualTo(PRINCIPAL_NAME)
                    .jsonPath("$.registrationId").isEqualTo(REGISTRATION_ID)
                    .jsonPath("$.accessToken").isEqualTo(ACCESS_TOKEN);
        }
    }

    private ConfigurableApplicationContext startGateway() {
        return new SpringApplicationBuilder(
                GatewayServiceApplication.class,
                SessionProbeConfiguration.class
        )
                .profiles("test")
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "server.port=0",
                        "spring.cloud.config.enabled=false",
                        "spring.data.redis.host=" + REDIS.getHost(),
                        "spring.data.redis.port=" + REDIS.getMappedPort(REDIS_PORT),
                        "spring.session.data.redis.namespace=" + SESSION_NAMESPACE,
                        "spring.session.timeout=5m",
                        "management.endpoints.web.exposure.include=*",
                        "management.endpoint.env.access=none",
                        "management.endpoint.sessions.access=none"
                )
                .run();
    }

    private WebTestClient webTestClient(ConfigurableApplicationContext context) {
        Integer port = Objects.requireNonNull(
                context.getEnvironment().getProperty("local.server.port", Integer.class)
        );
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionProbeConfiguration {

        @Bean
        RouterFunction<ServerResponse> sessionProbeRoute(
                ReactiveClientRegistrationRepository registrations,
                ServerOAuth2AuthorizedClientRepository authorizedClients
        ) {
            return RouterFunctions.route()
                    .POST("/actuator/session-probe", request -> request.session().flatMap(session -> {
                        var authority = new SimpleGrantedAuthority("ROLE_USERS");
                        var principal = new DefaultOAuth2User(
                                List.of(authority),
                                Map.of("sub", PRINCIPAL_NAME),
                                "sub"
                        );
                        var authentication = new OAuth2AuthenticationToken(
                                principal,
                                List.of(authority),
                                REGISTRATION_ID
                        );
                        session.getAttributes().put(
                                WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME,
                                new SecurityContextImpl(authentication)
                        );
                        return registrations.findByRegistrationId(REGISTRATION_ID)
                                .switchIfEmpty(Mono.error(
                                        new IllegalStateException("OAuth2 client registration is missing")
                                ))
                                .flatMap(registration -> authorizedClients.saveAuthorizedClient(
                                        authorizedClient(registration),
                                        authentication,
                                        request.exchange()
                                ))
                                .then(ServerResponse.noContent().build());
                    }))
                    .GET("/admin/session-probe.json", request -> request.principal()
                            .cast(OAuth2AuthenticationToken.class)
                            .flatMap(authentication -> authorizedClients
                                    .loadAuthorizedClient(REGISTRATION_ID, authentication, request.exchange())
                                    .flatMap(client -> ServerResponse.ok().bodyValue(Map.of(
                                            "principalName", authentication.getName(),
                                            "registrationId", client.getClientRegistration().getRegistrationId(),
                                            "accessToken", client.getAccessToken().getTokenValue()
                                    )))
                            ))
                    .build();
        }

        private OAuth2AuthorizedClient authorizedClient(ClientRegistration registration) {
            Instant issuedAt = Instant.now();
            var accessToken = new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    ACCESS_TOKEN,
                    issuedAt,
                    issuedAt.plusSeconds(300)
            );
            return new OAuth2AuthorizedClient(registration, PRINCIPAL_NAME, accessToken);
        }
    }
}
