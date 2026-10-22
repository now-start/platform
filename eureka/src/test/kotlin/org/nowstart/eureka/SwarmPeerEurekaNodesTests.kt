package org.nowstart.eureka

import com.netflix.appinfo.ApplicationInfoManager
import com.netflix.appinfo.InstanceInfo
import com.netflix.eureka.EurekaServerConfig
import com.netflix.eureka.cluster.PeerEurekaNode
import com.netflix.eureka.registry.PeerAwareInstanceRegistry
import com.netflix.eureka.resources.ServerCodecs
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean

class SwarmPeerEurekaNodesTests {

    private fun nodes(): SwarmPeerEurekaNodes {
        val manager = mock(ApplicationInfoManager::class.java)
        `when`(manager.getInfo())
            .thenReturn(
                InstanceInfo.Builder.newBuilder()
                    .setAppName("EUREKA")
                    .setHostName("local-container")
                    .setIPAddr("10.0.0.1")
                    .build(),
            )
        return spy(
            SwarmPeerEurekaNodes(
                mock(PeerAwareInstanceRegistry::class.java),
                mock(EurekaServerConfig::class.java),
                EurekaClientConfigBean(),
                mock(ServerCodecs::class.java),
                manager,
                mock(SwarmPeerResolver::class.java),
            ),
        )
    }

    @Test
    fun matchesTaskIpWhenRegistrationUsesContainerHostname() {
        val nodes = nodes()
        val instance =
            InstanceInfo.Builder.newBuilder()
                .setAppName("EUREKA")
                .setHostName("peer-container")
                .setIPAddr("10.0.0.2")
                .build()

        assertThat(nodes.isInstanceURL("http://10.0.0.2:8761/eureka/", instance)).isTrue()
        assertThat(nodes.isInstanceURL("http://10.0.0.3:8761/eureka/", instance)).isFalse()
        assertThat(nodes.isInstanceURL("http://peer-container:8761/eureka/", instance)).isTrue()
    }

    @Test
    fun recognizesOwnTaskIpWithoutExcludingOtherPeers() {
        val nodes = nodes()

        assertThat(nodes.isThisMyUrl("http://10.0.0.1:8761/eureka/")).isTrue()
        assertThat(nodes.isThisMyUrl("http://10.0.0.2:8761/eureka/")).isFalse()
    }

    private fun peer(url: String): PeerEurekaNode {
        val peer = mock(PeerEurekaNode::class.java)
        `when`(peer.getServiceUrl()).thenReturn(url)
        return peer
    }

    @Test
    fun retainsUnchangedConnectionsAndClosesRemovedPeersIncludingLastOne() {
        val nodes = nodes()
        val a = peer("http://10.0.0.2:8761/eureka/")
        val b = peer("http://10.0.0.3:8761/eureka/")
        doReturn(a).`when`(nodes).createPeerEurekaNode("http://10.0.0.2:8761/eureka/")
        doReturn(b).`when`(nodes).createPeerEurekaNode("http://10.0.0.3:8761/eureka/")

        nodes.updatePeerEurekaNodes(listOf("http://10.0.0.2:8761/eureka/"))
        nodes.updatePeerEurekaNodes(
            listOf("http://10.0.0.2:8761/eureka/", "http://10.0.0.3:8761/eureka/"),
        )
        assertThat(nodes.getPeerNodesView()).containsExactly(a, b)
        verify(nodes, times(1)).createPeerEurekaNode("http://10.0.0.2:8761/eureka/")

        nodes.updatePeerEurekaNodes(listOf("http://10.0.0.3:8761/eureka/"))
        verify(a).shutDown()
        assertThat(nodes.getPeerEurekaNodes()).containsExactly(b)

        nodes.updatePeerEurekaNodes(listOf())
        verify(b).shutDown()
        assertThat(nodes.getPeerEurekaNodes()).isEmpty()

        nodes.updatePeerEurekaNodes(listOf("http://10.0.0.2:8761/eureka/"))
        verify(nodes, times(2)).createPeerEurekaNode("http://10.0.0.2:8761/eureka/")
    }

    @Test
    fun failedRefreshKeepsWorkingPeersAndClosesPartiallyCreatedConnections() {
        val nodes = nodes()
        val a = peer("http://10.0.0.2:8761/eureka/")
        val b = peer("http://10.0.0.3:8761/eureka/")
        doReturn(a).`when`(nodes).createPeerEurekaNode("http://10.0.0.2:8761/eureka/")
        doReturn(b).`when`(nodes).createPeerEurekaNode("http://10.0.0.3:8761/eureka/")
        doThrow(IllegalStateException("connection setup failed"))
            .`when`(nodes)
            .createPeerEurekaNode("http://10.0.0.4:8761/eureka/")

        nodes.updatePeerEurekaNodes(listOf("http://10.0.0.2:8761/eureka/"))
        assertThatThrownBy(
                {
                    nodes.updatePeerEurekaNodes(
                        listOf(
                            "http://10.0.0.3:8761/eureka/",
                            "http://10.0.0.4:8761/eureka/",
                        ),
                    )
                },
            )
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(nodes.getPeerEurekaNodes()).containsExactly(a)
        verify(a, never()).shutDown()
        verify(b).shutDown()
    }
}
