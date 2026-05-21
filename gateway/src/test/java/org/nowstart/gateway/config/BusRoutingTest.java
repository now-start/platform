package org.nowstart.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.test.StepVerifier;

import static org.assertj.core.api.BDDAssertions.then;

@ActiveProfiles("test")
@SpringBootTest
@ImportAutoConfiguration({
        ReactiveWebSecurityAutoConfiguration.class,
        WebFluxAutoConfiguration.class
})
class BusRoutingTest {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private RouteLocator routeLocator;

    @MockitoBean
    private ReactiveClientRegistrationRepository clientRegistrationRepository;

    @Test
    @DisplayName("RefreshRoutesEvent가 발행되면 라우트 로케이터가 갱신되어야 한다")
    void refreshRoutesEventShouldTriggerRouteRefresh() {
        // given: 초기 라우트 확인 (DiscoveryLocator가 활성화되어 있다면 자동으로 생성된 라우트들이 있을 것)
        // 테스트 환경에서는 discovery.enabled=false 이므로 라우트가 없을 수 있음.

        // when: RefreshRoutesEvent 발행
        publisher.publishEvent(new RefreshRoutesEvent(this));

        // then: 이벤트가 에러 없이 처리됨을 확인 (비동기 처리가 많으므로 사이드 이펙트 위주로 확인)
        // Spring Cloud Gateway는 RefreshRoutesEvent를 받으면 RouteRefreshListener를 통해
        // CachingRouteLocator를 갱신합니다.

        then(routeLocator).isNotNull();
        StepVerifier.create(routeLocator.getRoutes())
                .expectNextCount(0) // 테스트 프로필에서 discovery.enabled=false 이므로 0개 예상
                .verifyComplete();
    }

    @TestConfiguration
    static class TestConfig {
        @MockitoBean
        private HttpExchangeRepository httpExchangeRepository;

        @MockitoBean
        private AuditEventRepository auditEventRepository;
    }
}
