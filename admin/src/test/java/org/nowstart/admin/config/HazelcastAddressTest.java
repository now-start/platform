package org.nowstart.admin.config;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HazelcastAddressTest {
    @Test
    void selectsOnlyTheConfiguredOverlayWithoutPeerDns() throws Exception {
        assertThat(HazelcastConfig.selectLocalAddress("10.0.11.0/24", List.of(
                InetAddress.getByName("10.0.3.6"), InetAddress.getByName("10.0.11.184"))))
                .isEqualTo("10.0.11.184");
        var config = new HazelcastConfig().adminHazelcastConfig("test",
                List.of("tasks.unavailable.invalid"), "127.0.0.0/8", 5701);
        assertThat(config.getNetworkConfig().getJoin().getTcpIpConfig().getMembers())
                .containsExactly("tasks.unavailable.invalid");
    }

    @Test
    void rejectsMissingOrAmbiguousLocalAddresses() throws Exception {
        var addresses = List.of(InetAddress.getByName("10.0.11.2"), InetAddress.getByName("10.0.11.3"));
        assertThatIllegalStateException().isThrownBy(() -> HazelcastConfig.selectLocalAddress("10.0.11.0/24", addresses));
        assertThatIllegalStateException().isThrownBy(() -> HazelcastConfig.selectLocalAddress("10.0.3.0/24", addresses));
        assertThatIllegalArgumentException().isThrownBy(() -> HazelcastConfig.selectLocalAddress("10.0.0.0/33", addresses));
    }
}
