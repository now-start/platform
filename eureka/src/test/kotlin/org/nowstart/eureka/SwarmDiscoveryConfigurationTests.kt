package org.nowstart.eureka

import com.netflix.appinfo.ApplicationInfoManager
import com.netflix.discovery.EurekaClientConfig
import com.netflix.eureka.EurekaServerConfig
import com.netflix.eureka.cluster.PeerEurekaNodes
import com.netflix.eureka.registry.PeerAwareInstanceRegistry
import com.netflix.eureka.resources.ServerCodecs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean

class SwarmDiscoveryConfigurationTests {

    @Test
    fun bootstrapAndSubsequentRegistryFetchesUseDirectPeersInsteadOfVip() {
        val resolver = mock(SwarmPeerResolver::class.java)
        `when`(resolver.resolve())
            .thenReturn(
                listOf("http://10.0.0.2:8761/eureka/"),
                listOf("http://10.0.0.3:8761/eureka/"),
                listOf(),
            )
        `when`(resolver.localUrl()).thenReturn("http://127.0.0.1:8761/eureka/")
        val config = SwarmDiscoveryConfiguration().eurekaClientConfigBean(resolver)
        config.getServiceUrl().put("defaultZone", "http://eureka:8761/eureka/")

        assertThat(config.getEurekaServerServiceUrls("defaultZone"))
            .containsExactly("http://10.0.0.2:8761/eureka/")
        assertThat(config.getEurekaServerServiceUrls("defaultZone"))
            .containsExactly("http://10.0.0.3:8761/eureka/")
        assertThat(config.getEurekaServerServiceUrls("defaultZone"))
            .containsExactly("http://127.0.0.1:8761/eureka/")
    }

    @Test
    fun disabledOutsideSwarm() {
        ApplicationContextRunner()
            .withUserConfiguration(SwarmDiscoveryConfiguration::class.java)
            .run({ context -> assertThat(context).doesNotHaveBean(SwarmPeerResolver::class.java) })
    }

    @Test
    fun bindsNormalEurekaClientPropertiesWhenSwarmIsEnabled() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java),
            )
            .withUserConfiguration(SwarmDiscoveryConfiguration::class.java)
            .withBean(
                PeerAwareInstanceRegistry::class.java,
                { mock(PeerAwareInstanceRegistry::class.java) },
            )
            .withBean(EurekaServerConfig::class.java, { mock(EurekaServerConfig::class.java) })
            .withBean(ServerCodecs::class.java, { mock(ServerCodecs::class.java) })
            .withBean(
                ApplicationInfoManager::class.java,
                { mock(ApplicationInfoManager::class.java) },
            )
            .withPropertyValues(
                "eureka.swarm.enabled=true",
                "eureka.swarm.network-cidr=127.0.0.0/8",
                "eureka.client.registry-fetch-interval-seconds=10",
            )
            .run(
                { context ->
                    assertThat(context).hasSingleBean(EurekaClientConfig::class.java)
                    assertThat(context).hasSingleBean(PeerEurekaNodes::class.java)
                    assertThat(
                            context
                                .getBean(EurekaClientConfigBean::class.java)
                                .getRegistryFetchIntervalSeconds(),
                        )
                        .isEqualTo(10)
                },
            )
    }
}
