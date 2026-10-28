package org.nowstart.gateway.monitoring

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CircuitBreakersEndpointTest {
    @Test
    fun snapshotDoesNotCreateCircuitsOrChangeTheirState() {
        val registry = CircuitBreakerRegistry.ofDefaults()
        val endpoint = CircuitBreakersEndpoint(registry)
        assertThat(endpoint.snapshot()["circuits"] as List<*>).isEmpty()
        val circuit = registry.circuitBreaker("barosteel")
        circuit.onError(1, TimeUnit.MILLISECONDS, IllegalStateException("backend unavailable"))
        circuit.transitionToOpenState()
        val snapshots = endpoint.snapshot()["circuits"] as List<*>
        val snapshot = snapshots.single() as Map<*, *>
        assertThat(snapshot["name"]).isEqualTo("barosteel")
        assertThat(snapshot["state"]).isEqualTo("OPEN")
        assertThat(snapshot["failedCalls"]).isEqualTo(1)
        assertThat(circuit.state.name).isEqualTo("OPEN")
        assertThat(registry.allCircuitBreakers).hasSize(1)
    }
}
