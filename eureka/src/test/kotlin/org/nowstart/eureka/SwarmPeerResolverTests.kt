package org.nowstart.eureka

import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import javax.naming.NamingException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test

class SwarmPeerResolverTests {
    @Test
    fun discoversAddedPeersAndReplacedTaskAddressesAndRemovesLastPeer() {
        val addresses = AtomicReference(setOf("10.0.0.1"))
        val resolver =
            SwarmPeerResolver(
                "tasks.eureka",
                8761,
                { addresses.get() },
                { setOf("10.0.0.1", "10.1.0.1") },
            )
        assertThat(resolver.localAddress).isEqualTo("10.0.0.1")
        assertThat(resolver.resolve()).isEmpty()
        addresses.set(setOf("10.0.0.1", "10.0.0.2", "10.0.0.3"))
        assertThat(resolver.resolve())
            .containsExactly("http://10.0.0.2:8761/eureka/", "http://10.0.0.3:8761/eureka/")
        addresses.set(setOf("10.0.0.1", "10.0.0.4"))
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.4:8761/eureka/")
        addresses.set(setOf("10.0.0.1"))
        assertThat(resolver.resolve()).isEmpty()
    }

    @Test
    fun keepsLastKnownPeersOnDnsFailureEmptyOrMissingSelf() {
        val addresses = AtomicReference<Set<String>?>(setOf("10.0.0.1", "10.0.0.2"))
        val resolver =
            SwarmPeerResolver(
                "tasks.eureka",
                8761,
                { addresses.get() ?: throw NamingException("temporary failure") },
                { setOf("10.0.0.1", "10.1.0.1") },
            )
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/")
        for (answer in listOf(null, emptySet(), setOf("10.1.0.1", "10.1.0.2"))) {
            addresses.set(answer)
            assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/")
        }
    }

    @Test
    fun retriesStartupUntilDnsContainsLocalAddress() {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val resolver =
            SwarmPeerResolver(
                "tasks.eureka",
                8761,
                {
                    when (++attempts) {
                        1 -> throw NamingException("not registered")
                        2 -> emptySet()
                        3 -> setOf("10.0.0.2")
                        else -> setOf("10.0.0.1", "10.0.0.2")
                    }
                },
                { setOf("10.0.0.1", "192.168.1.1") },
                { waits.add(it) },
            )
        assertThat(resolver.localAddress).isEqualTo("10.0.0.1")
        assertThat(waits).containsExactly(1000L, 1000L, 1000L)
        assertThat(resolver.localUrl()).isEqualTo("http://127.0.0.1:8761/eureka/")
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/")
    }

    @Test
    fun failsAfterBoundedRetriesForMissingOrAmbiguousLocalAddress() {
        for (local in listOf(setOf("192.168.1.1"), setOf("10.0.0.1", "10.0.0.2"))) {
            var attempts = 0
            var waits = 0
            assertThatIllegalStateException()
                .isThrownBy {
                    SwarmPeerResolver(
                        "tasks.eureka",
                        8761,
                        {
                            attempts++
                            setOf("10.0.0.1", "10.0.0.2")
                        },
                        { local },
                        { waits++ },
                    )
                }
                .withMessageContaining("after 10 attempts")
            assertThat(attempts).isEqualTo(10)
            assertThat(waits).isEqualTo(9)
        }
    }

    @Test
    fun retriesLocalInterfaceFailureAndIgnoresIpv6DnsAnswers() {
        var attempts = 0
        val resolver =
            SwarmPeerResolver(
                "tasks.eureka",
                8761,
                { setOf("10.0.0.1", "::1") },
                {
                    if (++attempts == 1) throw SocketException("temporary failure")
                    setOf("10.0.0.1", "::1")
                },
                {},
            )
        assertThat(resolver.localAddress).isEqualTo("10.0.0.1")
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun interruptedStartupRestoresInterruptFlag() {
        try {
            assertThatIllegalStateException()
                .isThrownBy {
                    SwarmPeerResolver(
                        "tasks.eureka",
                        8761,
                        { emptySet() },
                        { emptySet() },
                        { throw InterruptedException() },
                    )
                }
                .withCauseInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }
}
