package org.nowstart.eureka;

import java.util.List;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "eureka.swarm.enabled", havingValue = "true")
class SwarmDiscoveryConfiguration {

    @Bean
    SwarmPeerResolver swarmPeerResolver(
            @Value("${eureka.swarm.service:tasks.eureka}") String service,
            @Value("${server.port:8761}") int port) {
        return new SwarmPeerResolver(service, port);
    }

    @Bean
    @ConfigurationProperties("eureka.client")
    EurekaClientConfigBean eurekaClientConfigBean(SwarmPeerResolver resolver) {
        return new EurekaClientConfigBean() {
            @Override
            public List<String> getEurekaServerServiceUrls(String zone) {
                List<String> peers = resolver.resolve();
                // A singleton must still start. Never use the VIP for bootstrap
                // when other peers exist, since it could route back to this server.
                return peers.isEmpty() ? List.of(resolver.localUrl()) : peers;
            }
        };
    }

    @Bean(destroyMethod = "")
    PeerEurekaNodes peerEurekaNodes(PeerAwareInstanceRegistry registry,
            EurekaServerConfig serverConfig, EurekaClientConfig clientConfig,
            ServerCodecs codecs, ApplicationInfoManager applicationInfoManager,
            SwarmPeerResolver resolver) {
        return new SwarmPeerEurekaNodes(registry, serverConfig, clientConfig,
                codecs, applicationInfoManager, resolver);
    }
}
