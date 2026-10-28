package org.nowstart.gateway.monitoring

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Instant
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.stereotype.Component

/** 등록된 서킷만 읽는다. 조회 때문에 새 서킷을 생성하거나 상태를 전환하지 않는다. */
@Component
@Endpoint(id = "circuitbreakersnapshot")
class CircuitBreakersEndpoint(private val registry: CircuitBreakerRegistry) {
    @ReadOperation
    fun snapshot(): Map<String, Any> =
        mapOf(
            "capturedAt" to Instant.now().toString(),
            "circuits" to
                registry.allCircuitBreakers
                    .sortedBy { it.name }
                    .map { circuit ->
                        val metrics = circuit.metrics
                        mapOf(
                            "name" to circuit.name,
                            "state" to circuit.state.name,
                            "failureRate" to metrics.failureRate,
                            "slowCallRate" to metrics.slowCallRate,
                            "bufferedCalls" to metrics.numberOfBufferedCalls,
                            "failedCalls" to metrics.numberOfFailedCalls,
                            "successfulCalls" to metrics.numberOfSuccessfulCalls,
                            "notPermittedCalls" to metrics.numberOfNotPermittedCalls,
                            "minimumCalls" to circuit.circuitBreakerConfig.minimumNumberOfCalls,
                        )
                    },
        )
}
