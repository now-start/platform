package org.nowstart.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

class SecurityConfigAuditTest {
    @Test
    void recordsStandardDetailsOffEventLoopWithoutStartingSession() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AtomicReference<AuditEvent> captured = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        doAnswer(call -> {
            captured.set(call.getArgument(0));
            thread.set(Thread.currentThread().getName());
            return null;
        }).when(repository).add(any());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress("192.0.2.1", 1234)));
        StepVerifier.create(SecurityConfig.recordAudit(repository, exchange, "user", "AUTHENTICATION_SUCCESS"))
                .verifyComplete();
        assertThat(thread.get()).startsWith("boundedElastic");
        assertThat(captured.get().getData()).containsOnlyKeys("details");
        assertThat(captured.get().getData().get("details")).isEqualTo(Map.of("remoteAddress", "192.0.2.1"));
        assertThat(exchange.getSession().block().isStarted()).isFalse();
    }

    @Test
    void includesExistingSessionIdAndDoesNotPropagateStorageFailure() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        var session = exchange.getSession().block();
        session.start();
        doThrow(new IllegalStateException("test unavailable")).when(repository).add(any());
        StepVerifier.create(SecurityConfig.recordAudit(repository, exchange, "user", "AUTHORIZATION_FAILURE"))
                .verifyComplete();
        var captor = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).add(captor.capture());
        assertThat(captor.getValue().getData().get("details")).isEqualTo(Map.of("sessionId", session.getId()));
    }
}
