package org.nowstart.admin.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HazelcastAddressTest {
    @Test
    fun usesConfiguredDockerHostnameWithoutDependingOnTaskDns() {
        val config =
            HazelcastConfiguration()
                .hazelcastConfig(
                    "test",
                    listOf("tasks.unavailable.invalid"),
                    5701,
                    "localhost",
                )
        assertThat(config.getProperty("hazelcast.local.localAddress")).isEqualTo("localhost")
        assertThat(config.networkConfig.join.tcpIpConfig.members)
            .containsExactly("tasks.unavailable.invalid")
    }

    @Test
    fun delegatesAddressSelectionAndRetainsUnresolvedMembers() {
        val config =
            HazelcastConfiguration()
                .hazelcastConfig("test", listOf("tasks.unavailable.invalid"), 5701)
        assertThat(config.getProperty("hazelcast.local.localAddress")).isNull()
        assertThat(config.networkConfig.interfaces.isEnabled).isFalse()
        assertThat(config.networkConfig.join.tcpIpConfig.members)
            .containsExactly("tasks.unavailable.invalid")
        assertThat(config.networkConfig.join.tcpIpConfig.isEnabled).isTrue()
        assertThat(config.networkConfig.isPortAutoIncrement).isFalse()
    }
}
