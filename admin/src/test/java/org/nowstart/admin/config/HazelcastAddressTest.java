package org.nowstart.admin.config;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HazelcastAddressTest {
    @Test
    void selectsLocalTaskAddressInsteadOfBridgeOrRemoteTaskWithoutFixedSubnet() throws Exception {
        var bridge = InetAddress.getByName("172.18.0.11");
        var remoteTask = InetAddress.getByName("192.168.50.8");
        var localTask = InetAddress.getByName("192.168.50.7");
        assertThat(HazelcastConfig.selectLocalAddress(List.of(remoteTask, localTask), Set.of(bridge, localTask)))
                .isEqualTo("192.168.50.7");
    }

    @Test
    void failsInsteadOfAdvertisingAnUnrelatedLocalAddress() throws Exception {
        var remoteTask = InetAddress.getByName("192.168.50.8");
        var bridge = InetAddress.getByName("172.18.0.11");
        assertThatIllegalStateException()
                .isThrownBy(() -> HazelcastConfig.selectLocalAddress(List.of(remoteTask), Set.of(bridge)))
                .withMessageContaining("No local IPv4 address");
    }

    @Test
    void selectsIpv4WhenDnsAlsoReturnsIpv6() throws Exception {
        var ipv6 = InetAddress.getByName("::1");
        var ipv4 = InetAddress.getByName("127.0.0.1");
        assertThat(HazelcastConfig.selectLocalAddress(List.of(ipv6, ipv4), Set.of(ipv6, ipv4)))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void resolvesLocalHostnameWithoutFilteringRemoteDiscoveryCandidates() throws Exception {
        var config = new HazelcastConfig().adminHazelcastConfig("admin-test", List.of("localhost"));
        assertThat(config.getProperty("hazelcast.local.localAddress")).isEqualTo("127.0.0.1");
        assertThat(config.getNetworkConfig().getInterfaces().isEnabled()).isFalse();
        assertThat(config.getNetworkConfig().getJoin().getTcpIpConfig().getMembers()).containsExactly("localhost");
    }
}
