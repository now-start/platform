package org.nowstart.eureka;

import java.util.List;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SwarmPeerEurekaNodesTests {

    private SwarmPeerEurekaNodes nodes() {
        var manager = mock(ApplicationInfoManager.class);
        when(manager.getInfo()).thenReturn(InstanceInfo.Builder.newBuilder()
                .setAppName("EUREKA").setHostName("local-container").setIPAddr("10.0.0.1").build());
        return spy(new SwarmPeerEurekaNodes(mock(PeerAwareInstanceRegistry.class),
                mock(EurekaServerConfig.class), new EurekaClientConfigBean(),
                mock(ServerCodecs.class), manager,
                mock(SwarmPeerResolver.class)));
    }

    @Test
    void matchesTaskIpWhenRegistrationUsesContainerHostname() {
        var nodes = nodes();
        var instance = InstanceInfo.Builder.newBuilder().setAppName("EUREKA")
                .setHostName("peer-container").setIPAddr("10.0.0.2").build();

        assertThat(nodes.isInstanceURL("http://10.0.0.2:8761/eureka/", instance)).isTrue();
        assertThat(nodes.isInstanceURL("http://10.0.0.3:8761/eureka/", instance)).isFalse();
        assertThat(nodes.isInstanceURL("http://peer-container:8761/eureka/", instance)).isTrue();
    }

    @Test
    void recognizesOwnTaskIpWithoutExcludingOtherPeers() {
        var nodes = nodes();

        assertThat(nodes.isThisMyUrl("http://10.0.0.1:8761/eureka/")).isTrue();
        assertThat(nodes.isThisMyUrl("http://10.0.0.2:8761/eureka/")).isFalse();
    }

    private PeerEurekaNode peer(String url) {
        var peer = mock(PeerEurekaNode.class);
        when(peer.getServiceUrl()).thenReturn(url);
        return peer;
    }

    @Test
    void retainsUnchangedConnectionsAndClosesRemovedPeersIncludingLastOne() {
        var nodes = nodes();
        var a = peer("http://10.0.0.2:8761/eureka/");
        var b = peer("http://10.0.0.3:8761/eureka/");
        doReturn(a).when(nodes).createPeerEurekaNode("http://10.0.0.2:8761/eureka/");
        doReturn(b).when(nodes).createPeerEurekaNode("http://10.0.0.3:8761/eureka/");

        nodes.updatePeerEurekaNodes(List.of("http://10.0.0.2:8761/eureka/"));
        nodes.updatePeerEurekaNodes(List.of("http://10.0.0.2:8761/eureka/", "http://10.0.0.3:8761/eureka/"));
        assertThat(nodes.getPeerNodesView()).containsExactly(a, b);
        verify(nodes, times(1)).createPeerEurekaNode("http://10.0.0.2:8761/eureka/");

        nodes.updatePeerEurekaNodes(List.of("http://10.0.0.3:8761/eureka/"));
        verify(a).shutDown();
        assertThat(nodes.getPeerEurekaNodes()).containsExactly(b);

        nodes.updatePeerEurekaNodes(List.of());
        verify(b).shutDown();
        assertThat(nodes.getPeerEurekaNodes()).isEmpty();

        nodes.updatePeerEurekaNodes(List.of("http://10.0.0.2:8761/eureka/"));
        verify(nodes, times(2)).createPeerEurekaNode("http://10.0.0.2:8761/eureka/");
    }

    @Test
    void failedRefreshKeepsWorkingPeersAndClosesPartiallyCreatedConnections() {
        var nodes = nodes();
        var a = peer("http://10.0.0.2:8761/eureka/");
        var b = peer("http://10.0.0.3:8761/eureka/");
        doReturn(a).when(nodes).createPeerEurekaNode("http://10.0.0.2:8761/eureka/");
        doReturn(b).when(nodes).createPeerEurekaNode("http://10.0.0.3:8761/eureka/");
        doThrow(new IllegalStateException("connection setup failed"))
                .when(nodes).createPeerEurekaNode("http://10.0.0.4:8761/eureka/");

        nodes.updatePeerEurekaNodes(List.of("http://10.0.0.2:8761/eureka/"));
        assertThatThrownBy(() -> nodes.updatePeerEurekaNodes(List.of(
                "http://10.0.0.3:8761/eureka/", "http://10.0.0.4:8761/eureka/")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(nodes.getPeerEurekaNodes()).containsExactly(a);
        verify(a, never()).shutDown();
        verify(b).shutDown();
    }
}
