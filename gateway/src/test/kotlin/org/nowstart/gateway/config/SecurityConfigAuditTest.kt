package org.nowstart.gateway.config

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.nowstart.gateway.audit.AuditClientAddressResolver
import org.springframework.boot.actuate.audit.AuditEvent
import org.springframework.boot.actuate.audit.AuditEventRepository
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.test.StepVerifier

class SecurityConfigAuditTest {
    @Test
    fun recordsStandardDetailsOffEventLoopWithoutStartingSession() {
        val repository: AuditEventRepository = mock(AuditEventRepository::class.java)
        val captured: AtomicReference<AuditEvent> = AtomicReference()
        val thread: AtomicReference<String> = AtomicReference()
        doAnswer(
                { call ->
                    captured.set(call.getArgument(0))
                    thread.set(Thread.currentThread().getName())
                    null
                },
            )
            .`when`(repository)
            .add(any())
        val exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.get("/").remoteAddress(InetSocketAddress("192.0.2.1", 1234)),
            )
        StepVerifier.create(
                SecurityConfig.recordAudit(repository, exchange, "user", "AUTHENTICATION_SUCCESS"),
            )
            .verifyComplete()
        assertThat(thread.get()).startsWith("boundedElastic")
        assertThat(captured.get().getData()).containsOnlyKeys("details")
        assertThat(captured.get().getData().get("details"))
            .isEqualTo(mapOf("remoteAddress" to "192.0.2.1"))
        assertThat(requireNotNull(exchange.session.block()).isStarted()).isFalse()
    }

    @Test
    fun includesExistingSessionIdAndDoesNotPropagateStorageFailure() {
        val repository: AuditEventRepository = mock(AuditEventRepository::class.java)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
        val session = requireNotNull(exchange.session.block())
        session.start()
        doThrow(IllegalStateException("test unavailable")).`when`(repository).add(any())
        StepVerifier.create(
                SecurityConfig.recordAudit(repository, exchange, "user", "AUTHORIZATION_FAILURE"),
            )
            .verifyComplete()
        val captor = org.mockito.ArgumentCaptor.forClass(AuditEvent::class.java)
        verify(repository).add(captor.capture())
        assertThat(captor.getValue().getData().get("details"))
            .isEqualTo(mapOf("sessionId" to session.getId()))
    }

    @Test
    fun recordsForwardedClientIpOnlyFromTrustedProxy() {
        val repository = org.springframework.boot.actuate.audit.InMemoryAuditEventRepository()
        val exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                    .remoteAddress(InetSocketAddress("10.0.0.7", 1234))
                    .header("X-Forwarded-For", "203.0.113.40, 10.0.0.2"),
            )
        SecurityConfig.recordAudit(
                repository,
                exchange,
                "user",
                "AUTHENTICATION_SUCCESS",
                AuditClientAddressResolver("10\\.0\\.0\\.[0-9]+"),
            )
            .block()
        assertThat(repository.find(null, null, null).single().data["details"])
            .isEqualTo(mapOf("remoteAddress" to "203.0.113.40"))
    }
}
