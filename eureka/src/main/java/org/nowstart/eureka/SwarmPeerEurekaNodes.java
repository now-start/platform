package org.nowstart.eureka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.transport.Jersey3ReplicationClient;

final class SwarmPeerEurekaNodes extends PeerEurekaNodes {

    private final SwarmPeerResolver resolver;
    private volatile List<PeerEurekaNode> nodes = List.of();
    private boolean stopped;

    SwarmPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig serverConfig,
            EurekaClientConfig clientConfig, ServerCodecs codecs,
            ApplicationInfoManager applicationInfoManager, SwarmPeerResolver resolver) {
        super(registry, serverConfig, clientConfig, codecs, applicationInfoManager);
        this.resolver = resolver;
    }

    @Override
    protected List<String> resolvePeerUrls() {
        return resolver.resolve();
    }

    @Override
    public boolean isInstanceURL(String url, InstanceInfo instance) {
        String host = hostFromUrl(url);
        return host != null && host.equals(instance.getIPAddr()) || super.isInstanceURL(url, instance);
    }

    @Override
    protected PeerEurekaNode createPeerEurekaNode(String url) {
        return new PeerEurekaNode(registry, hostFromUrl(url), url,
                Jersey3ReplicationClient.createReplicationClient(serverConfig, serverCodecs, url),
                serverConfig);
    }

    @Override
    protected synchronized void updatePeerEurekaNodes(List<String> urls) {
        if (stopped) {
            return;
        }
        // Eureka's default updater ignores empty lists, leaving a departed last
        // peer behind. Keep an immutable snapshot that also supports zero peers.
        Map<String, PeerEurekaNode> remaining = new LinkedHashMap<>();
        nodes.forEach(node -> remaining.put(node.getServiceUrl(), node));
        List<PeerEurekaNode> updated = new ArrayList<>();
        List<PeerEurekaNode> created = new ArrayList<>();
        try {
            for (String url : urls.stream().distinct().toList()) {
                PeerEurekaNode node = remaining.remove(url);
                if (node == null) {
                    node = createPeerEurekaNode(url);
                    created.add(node);
                }
                updated.add(node);
            }
        }
        catch (RuntimeException ex) {
            created.forEach(PeerEurekaNode::shutDown);
            throw ex;
        }
        nodes = List.copyOf(updated);
        remaining.values().forEach(PeerEurekaNode::shutDown);
    }

    @Override
    public List<PeerEurekaNode> getPeerEurekaNodes() {
        return nodes;
    }

    @Override
    public List<PeerEurekaNode> getPeerNodesView() {
        return nodes;
    }

    @Override
    public synchronized void shutdown() {
        stopped = true;
        super.shutdown();
        List<PeerEurekaNode> previous = nodes;
        nodes = List.of();
        previous.forEach(PeerEurekaNode::shutDown);
    }
}
