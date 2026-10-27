package org.nowstart.gateway.config

import java.net.InetAddress
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test

class HazelcastAddressTest {
    private fun addresses(vararg values: String) = values.map(InetAddress::getByName)

    @Test
    fun selectsOnlyTheConfiguredOverlay() {
        assertThat(
                HazelcastConfiguration.selectLocalAddress(
                    "10.0.11.0/24",
                    addresses("10.0.3.6", "10.0.11.184", "10.0.12.2", "::1", "10.0.11.184"),
                ),
            )
            .isEqualTo("10.0.11.184")
    }

    @Test
    fun rejectsMissingOrAmbiguousLocalAddresses() {
        assertThatIllegalStateException().isThrownBy {
            HazelcastConfiguration.selectLocalAddress("10.0.11.0/24", addresses("10.0.3.6"))
        }
        assertThatIllegalStateException().isThrownBy {
            HazelcastConfiguration.selectLocalAddress(
                "10.0.11.0/24",
                addresses("10.0.11.2", "10.0.11.3"),
            )
        }
    }

    @Test
    fun rejectsInvalidCidr() {
        for (cidr in listOf("10.0.11.*", "localhost/8", "10.0.11.0/33", "::1/128")) {
            assertThatIllegalArgumentException().isThrownBy {
                HazelcastConfiguration.selectLocalAddress(cidr, addresses("127.0.0.1"))
            }
        }
    }

    @Test
    fun configSelectsLocalAddressWithoutResolvingPeerDnsOrFilteringPeers() {
        val config =
            HazelcastConfiguration()
                .hazelcastConfig(
                    "test",
                    listOf("tasks.unavailable.invalid"),
                    "127.0.0.0/8",
                    5701,
                )
        assertThat(config.getProperty("hazelcast.local.localAddress")).isEqualTo("127.0.0.1")
        assertThat(config.networkConfig.interfaces.isEnabled).isFalse()
        assertThat(config.networkConfig.join.tcpIpConfig.members)
            .containsExactly("tasks.unavailable.invalid")
        assertThat(config.networkConfig.isPortAutoIncrement).isFalse()
    }
}
