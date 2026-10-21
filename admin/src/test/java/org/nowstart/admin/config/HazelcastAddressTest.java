package org.nowstart.admin.config;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HazelcastAddressTest {
    @Test
    void prefersExactLocalTaskAddressOverSubnetCandidates() throws Exception {
        assertThat(HazelcastConfig.selectLocalAddress(
                List.of(ip("192.168.50.8"), ip("192.168.50.7")),
                List.of(local("172.18.0.11", 16), local("192.168.50.7", 24), local("192.168.50.9", 24))))
                .isEqualTo("192.168.50.7");
    }

    @Test
    void selectsLocalSubnetWhenDnsDoesNotContainSelf() throws Exception {
        assertThat(HazelcastConfig.selectLocalAddress(
                List.of(ip("192.168.50.8"), ip("192.168.50.9")),
                List.of(local("172.18.0.11", 16), local("192.168.50.7", 24))))
                .isEqualTo("192.168.50.7");
    }

    @Test
    void usesActualPrefixIncludingPartialBytes() throws Exception {
        assertThat(HazelcastConfig.selectLocalAddress(List.of(ip("192.168.50.200")),
                List.of(local("192.168.50.7", 25), local("192.168.50.130", 25))))
                .isEqualTo("192.168.50.130");
    }

    @Test
    void failsInsteadOfAdvertisingAnUnrelatedLocalAddress() throws Exception {
        assertThatIllegalStateException().isThrownBy(() -> HazelcastConfig.selectLocalAddress(
                List.of(ip("192.168.50.8")), List.of(local("172.18.0.11", 16))))
                .withMessageContaining("No local IPv4 address")
                .withMessageContaining("192.168.50.8", "172.18.0.11/16");
    }

    @Test
    void rejectsAmbiguousSubnets() throws Exception {
        assertThatIllegalStateException().isThrownBy(() -> HazelcastConfig.selectLocalAddress(
                List.of(ip("192.168.50.8"), ip("192.168.60.8")),
                List.of(local("192.168.50.7", 24), local("192.168.60.7", 24))))
                .withMessageContaining("Multiple local IPv4 addresses");
    }

    @Test
    void rejectsMultipleExactMatches() throws Exception {
        assertThatIllegalStateException().isThrownBy(() -> HazelcastConfig.selectLocalAddress(
                List.of(ip("192.168.50.7"), ip("192.168.60.7")),
                List.of(local("192.168.50.7", 24), local("192.168.60.7", 24))))
                .withMessageContaining("Multiple local IPv4 addresses");
    }

    @Test
    void ignoresIpv6AndDuplicateDnsEntries() throws Exception {
        assertThat(HazelcastConfig.selectLocalAddress(
                List.of(ip("::1"), ip("127.0.0.1"), ip("127.0.0.1")),
                List.of(local("::1", 128), local("127.0.0.1", 8))))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void rejectsEmptyDnsAndInvalidSubnetPrefixes() throws Exception {
        assertThatIllegalStateException().isThrownBy(() -> HazelcastConfig.selectLocalAddress(
                List.of(), List.of(local("192.168.50.7", 24))));
        for (int prefix : List.of(-1, 0, 33)) {
            assertThatIllegalStateException().isThrownBy(() -> HazelcastConfig.selectLocalAddress(
                    List.of(ip("192.168.50.8")), List.of(local("192.168.50.7", prefix))));
        }
    }

    @Test
    void resolvesLocalHostnameWithoutFilteringRemoteDiscoveryCandidates() throws Exception {
        var config = new HazelcastConfig().adminHazelcastConfig("admin-test", List.of("localhost"), "");
        assertThat(config.getProperty("hazelcast.local.localAddress")).isEqualTo("127.0.0.1");
        assertThat(config.getNetworkConfig().getInterfaces().isEnabled()).isFalse();
        assertThat(config.getNetworkConfig().getJoin().getTcpIpConfig().getMembers()).containsExactly("localhost");
    }

    @Test
    void startsWithoutPeerDnsWhenBindHostIsConfigured() throws Exception {
        var config = new HazelcastConfig().adminHazelcastConfig(
                "admin-test", List.of("unavailable-admin.invalid"), "localhost");
        assertThat(config.getProperty("hazelcast.local.localAddress")).isEqualTo("127.0.0.1");
        assertThat(config.getNetworkConfig().getJoin().getTcpIpConfig().getMembers())
                .containsExactly("unavailable-admin.invalid");
    }
    private static InetAddress ip(String address) throws Exception {
        return InetAddress.getByName(address);
    }

    private static HazelcastConfig.LocalAddress local(String address, int prefix) throws Exception {
        return new HazelcastConfig.LocalAddress(ip(address), prefix);
    }
}
