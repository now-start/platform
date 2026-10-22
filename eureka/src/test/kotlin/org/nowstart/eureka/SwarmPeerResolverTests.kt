package org.nowstart.eureka

import java.util.concurrent.atomic.AtomicReference
import javax.naming.NamingException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SwarmPeerResolverTests {

    @Test
    fun discoversAddedPeersAndReplacedTaskAddressesAndRemovesLastPeer() {
        val addresses = AtomicReference<Set<String>?>(setOf("10.0.0.1"))
        val resolver =
            SwarmPeerResolver(
                "tasks.eureka",
                8761,
                { _ -> requireNotNull(addresses.get()) },
                { setOf("10.0.0.1", "10.1.0.1") },
            )
        assertThat(resolver.resolve()).isEmpty()

        addresses.set(setOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.1.0.1"))
        assertThat(resolver.resolve())
            .containsExactly(
                "http://10.0.0.2:8761/eureka/",
                "http://10.0.0.3:8761/eureka/",
            )

        addresses.set(setOf("10.0.0.1", "10.0.0.4"))
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.4:8761/eureka/")

        addresses.set(setOf("10.0.0.1"))
        assertThat(resolver.resolve()).isEmpty()
    }

    @Test
    fun keepsLastKnownPeersOnDnsFailureOrEmptyOrUnrelatedAnswers() {
        val addresses = AtomicReference<Set<String>?>(setOf("10.0.0.1", "10.0.0.2"))
        val resolver =
            SwarmPeerResolver(
                "tasks.eureka",
                8761,
                { name ->
                    if (addresses.get() == null) {
                        throw NamingException("temporary failure")
                    }
                    requireNotNull(addresses.get())
                },
                { setOf("10.0.0.1") },
            )

        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/")
        addresses.set(null)
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/")
        addresses.set(setOf())
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/")
        addresses.set(setOf("10.9.0.2"))
        assertThat(resolver.resolve()).containsExactly("http://10.0.0.2:8761/eureka/")
    }

    @Test
    fun permitsStartupBeforeDnsIsAvailable() {
        val resolver =
            SwarmPeerResolver(
                "tasks.eureka",
                8761,
                { name -> throw NamingException("not yet registered") },
                { setOf("10.0.0.1") },
            )
        assertThat(resolver.resolve()).isEmpty()
    }
}
