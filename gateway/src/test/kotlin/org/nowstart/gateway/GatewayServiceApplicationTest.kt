package org.nowstart.gateway

import org.assertj.core.api.BDDAssertions.then
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.audit.AuditEventRepository
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory
import org.springframework.context.ApplicationContext
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean

@ActiveProfiles("test")
@SpringBootTest
class GatewayServiceApplicationTest {

    @field:MockitoBean
    private lateinit var clientRegistrationRepository: ReactiveClientRegistrationRepository

    @field:Autowired private lateinit var applicationContext: ApplicationContext

    @field:Autowired(required = false)
    private lateinit var httpExchangeRepository: HttpExchangeRepository

    @field:Autowired(required = false)
    private lateinit var auditEventRepository: AuditEventRepository

    @Test
    @DisplayName("애플리케이션 컨텍스트가 정상적으로 로드되어야 한다")
    fun contextLoads() {
        then(applicationContext).isNotNull()
    }

    @Test
    @DisplayName("필수 빈들이 정상적으로 생성되어야 한다")
    fun beansAreCreated() {
        then(httpExchangeRepository).isNotNull()
        then(auditEventRepository).isNotNull()
    }

    @Test
    @DisplayName("전역 CircuitBreaker가 애플리케이션 컨텍스트에 없어야 한다")
    fun circuitBreakerShouldNotBeConfigured() {
        then(applicationContext.getBeanNamesForType(ReactiveCircuitBreakerFactory::class.java))
            .isEmpty()
    }
}
