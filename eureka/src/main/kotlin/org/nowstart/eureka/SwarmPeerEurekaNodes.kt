package org.nowstart.eureka

import com.netflix.appinfo.ApplicationInfoManager
import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.EurekaClientConfig
import com.netflix.eureka.EurekaServerConfig
import com.netflix.eureka.cluster.PeerEurekaNode
import com.netflix.eureka.cluster.PeerEurekaNodes
import com.netflix.eureka.registry.PeerAwareInstanceRegistry
import com.netflix.eureka.resources.ServerCodecs
import com.netflix.eureka.transport.Jersey3ReplicationClient

class SwarmPeerEurekaNodes(
    registry: PeerAwareInstanceRegistry,
    serverConfig: EurekaServerConfig,
    clientConfig: EurekaClientConfig,
    codecs: ServerCodecs,
    applicationInfoManager: ApplicationInfoManager,
    private val resolver: SwarmPeerResolver,
) : PeerEurekaNodes(registry, serverConfig, clientConfig, codecs, applicationInfoManager) {
    @Volatile private var nodes: List<PeerEurekaNode> = emptyList()
    private var stopped = false

    override fun resolvePeerUrls(): List<String> = resolver.resolve()

    override fun isInstanceURL(url: String, instance: InstanceInfo): Boolean {
        val host = hostFromUrl(url)
        return (host != null && host == instance.ipAddr) || super.isInstanceURL(url, instance)
    }

    public override fun createPeerEurekaNode(url: String): PeerEurekaNode =
        PeerEurekaNode(
            registry,
            hostFromUrl(url),
            url,
            Jersey3ReplicationClient.createReplicationClient(serverConfig, serverCodecs, url),
            serverConfig,
        )

    @Synchronized
    public override fun updatePeerEurekaNodes(urls: List<String>) {
        if (stopped) return
        // Eureka의 기본 갱신 로직은 빈 목록을 무시해 마지막으로 떠난 피어를 남겨 둔다.
        // 피어가 없는 상태도 반영할 수 있도록 불변 목록을 유지한다.
        val remaining = nodes.associateByTo(linkedMapOf()) { it.serviceUrl }
        val updated = mutableListOf<PeerEurekaNode>()
        val created = mutableListOf<PeerEurekaNode>()
        try {
            for (url in urls.distinct()) {
                val node =
                    remaining.remove(url) ?: createPeerEurekaNode(url).also { created.add(it) }
                updated.add(node)
            }
        } catch (ex: RuntimeException) {
            created.forEach { it.shutDown() }
            throw ex
        }
        nodes = java.util.List.copyOf(updated)
        remaining.values.forEach { it.shutDown() }
    }

    override fun getPeerEurekaNodes(): List<PeerEurekaNode> = nodes

    override fun getPeerNodesView(): List<PeerEurekaNode> = nodes

    @Synchronized
    override fun shutdown() {
        stopped = true
        super.shutdown()
        val previous = nodes
        nodes = emptyList()
        previous.forEach { it.shutDown() }
    }
}
