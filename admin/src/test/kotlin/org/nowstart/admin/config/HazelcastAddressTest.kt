package org.nowstart.admin.config

import java.net.InetAddress
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class HazelcastAddressTest {
    @Test
    fun selectsOnlyTheConfiguredOverlayWithoutPeerDns() {
        assertThat(
                HazelcastConfig.selectLocalAddress(
                    "10.0.11.0/24",
                    listOf(
                        InetAddress.getByName("10.0.3.6"),
                        InetAddress.getByName("10.0.11.184"),
                    ),
                ),
            )
            .isEqualTo("10.0.11.184")
        val config =
            HazelcastConfig()
                .adminHazelcastConfig(
                    "test",
                    listOf("tasks.unavailable.invalid"),
                    "127.0.0.0/8",
                    5701,
                )
        assertThat(config.getNetworkConfig().getJoin().getTcpIpConfig().getMembers())
            .containsExactly("tasks.unavailable.invalid")
    }

    @Test
    fun rejectsMissingOrAmbiguousLocalAddresses() {
        val addresses =
            listOf(InetAddress.getByName("10.0.11.2"), InetAddress.getByName("10.0.11.3"))
        assertThatIllegalStateException()
            .isThrownBy({ HazelcastConfig.selectLocalAddress("10.0.11.0/24", addresses) })
        assertThatIllegalStateException()
            .isThrownBy({ HazelcastConfig.selectLocalAddress("10.0.3.0/24", addresses) })
        assertThatIllegalArgumentException()
            .isThrownBy({ HazelcastConfig.selectLocalAddress("10.0.0.0/33", addresses) })
    }
}
