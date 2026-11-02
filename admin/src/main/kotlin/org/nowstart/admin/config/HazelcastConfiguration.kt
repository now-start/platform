package org.nowstart.admin.config

import com.hazelcast.config.Config
import com.hazelcast.config.InMemoryFormat
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MergePolicyConfig
import de.codecentric.boot.admin.server.config.AdminServerHazelcastAutoConfiguration.DEFAULT_NAME_EVENT_STORE_MAP
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class HazelcastConfiguration {
    @Bean
    fun hazelcastConfig(
        @Value("\${app.cluster.name:\${spring.application.name}}") name: String,
        @Value("\${app.cluster.members}") members: List<String>,
        @Value("\${app.cluster.port:5701}") port: Int,
    ): Config {
        val config = Config().setClusterName(name)
        config.setProperty("hazelcast.shutdownhook.policy", "GRACEFUL")
        config.setProperty("hazelcast.graceful.shutdown.max.wait", "90")
        val network = config.networkConfig.setPort(port).setPortAutoIncrement(false)
        network.join.autoDetectionConfig.setEnabled(false)
        network.join.multicastConfig.setEnabled(false)
        network.join.tcpIpConfig.setEnabled(true).setMembers(members)
        config.addMapConfig(createSharedMap("default"))
        config.addMapConfig(
            createSharedMap(DEFAULT_NAME_EVENT_STORE_MAP).setInMemoryFormat(InMemoryFormat.OBJECT),
        )
        return config
    }

    private fun createSharedMap(name: String): MapConfig =
        MapConfig(name)
            .setBackupCount(1)
            .setAsyncBackupCount(0)
            .setMergePolicyConfig(
                MergePolicyConfig("com.hazelcast.spi.merge.PutIfAbsentMergePolicy", 100),
            )
}
