package org.nowstart.gateway.audit

import java.net.InetSocketAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest

class AuditClientAddressResolverTest {
    private val resolver = AuditClientAddressResolver("10\\.0\\.0\\.[0-9]+")

    @Test
    fun resolvesClientBehindTrustedProxiesAndIgnoresSpoofedLeftmostAddress() {
        assertThat(resolve("10.0.0.7", "203.0.113.40, 10.0.0.2")).isEqualTo("203.0.113.40")
        assertThat(resolve("10.0.0.7", "192.0.2.99, 203.0.113.40, 10.0.0.2"))
            .isEqualTo("203.0.113.40")
        assertThat(resolve("10.0.0.7", "2001:db8::40, 10.0.0.2")).isEqualTo("2001:db8::40")
    }

    @Test
    fun untrustedConnectionsOrInvalidHeadersUseConnectionAddress() {
        assertThat(resolve("198.51.100.8", "203.0.113.40")).isEqualTo("198.51.100.8")
        assertThat(resolve("10.0.0.7", "invalid, 203.0.113.40")).isEqualTo("10.0.0.7")
        assertThat(resolve("10.0.0.7", "")).isEqualTo("10.0.0.7")
        assertThat(
                resolver.resolve(
                    MockServerHttpRequest.get("/")
                        .remoteAddress(InetSocketAddress("10.0.0.7", 1234))
                        .build(),
                ),
            )
            .isEqualTo("10.0.0.7")
    }

    private fun resolve(remote: String, forwarded: String): String? =
        resolver.resolve(
            MockServerHttpRequest.get("/")
                .remoteAddress(InetSocketAddress(remote, 1234))
                .header("X-Forwarded-For", forwarded)
                .build(),
        )
}
