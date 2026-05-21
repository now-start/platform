package org.nowstart.gateway;

import static org.assertj.core.api.BDDAssertions.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@SpringBootTest
class GatewayServiceApplicationTest {

    @MockitoBean
    private ReactiveClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private HttpExchangeRepository httpExchangeRepository;

    @Autowired(required = false)
    private AuditEventRepository auditEventRepository;

    @Test
    @DisplayName("애플리케이션 컨텍스트가 정상적으로 로드되어야 한다")
    void contextLoads() {
        then(applicationContext).isNotNull();
    }

    @Test
    @DisplayName("필수 빈들이 정상적으로 생성되어야 한다")
    void beansAreCreated() {
        then(httpExchangeRepository).isNotNull();
        then(auditEventRepository).isNotNull();
    }

}
