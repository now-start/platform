package org.nowstart.eureka;

import java.util.List;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SwarmDiscoveryConfigurationTests {

    @Test
    void bootstrapAndSubsequentRegistryFetchesUseDirectPeersInsteadOfVip() {
        var resolver = mock(SwarmPeerResolver.class);
        when(resolver.resolve()).thenReturn(
                List.of("http://10.0.0.2:8761/eureka/"),
                List.of("http://10.0.0.3:8761/eureka/"), List.of());
        when(resolver.localUrl()).thenReturn("http://127.0.0.1:8761/eureka/");
        var config = new SwarmDiscoveryConfiguration().eurekaClientConfigBean(resolver);
        config.getServiceUrl().put("defaultZone", "http://eureka:8761/eureka/");

        assertThat(config.getEurekaServerServiceUrls("defaultZone"))
                .containsExactly("http://10.0.0.2:8761/eureka/");
        assertThat(config.getEurekaServerServiceUrls("defaultZone"))
                .containsExactly("http://10.0.0.3:8761/eureka/");
        assertThat(config.getEurekaServerServiceUrls("defaultZone"))
                .containsExactly("http://127.0.0.1:8761/eureka/");
    }

    @Test
    void disabledOutsideSwarm() {
        new ApplicationContextRunner()
                .withUserConfiguration(SwarmDiscoveryConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(SwarmPeerResolver.class));
    }

    @Test
    void bindsNormalEurekaClientPropertiesWhenSwarmIsEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(SwarmDiscoveryConfiguration.class)
                .withBean(PeerAwareInstanceRegistry.class, () -> mock(PeerAwareInstanceRegistry.class))
                .withBean(EurekaServerConfig.class, () -> mock(EurekaServerConfig.class))
                .withBean(ServerCodecs.class, () -> mock(ServerCodecs.class))
                .withBean(ApplicationInfoManager.class, () -> mock(ApplicationInfoManager.class))
                .withPropertyValues("eureka.swarm.enabled=true",
                        "eureka.client.registry-fetch-interval-seconds=10")
                .run(context -> {
                    assertThat(context).hasSingleBean(EurekaClientConfig.class);
                    assertThat(context).hasSingleBean(PeerEurekaNodes.class);
                    assertThat(context.getBean(EurekaClientConfigBean.class)
                            .getRegistryFetchIntervalSeconds()).isEqualTo(10);
                });
    }
}
