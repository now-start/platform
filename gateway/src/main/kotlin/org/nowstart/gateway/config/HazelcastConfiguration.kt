package org.nowstart.gateway.config

import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.spring.session.HazelcastIndexedSessionRepository
import com.hazelcast.spring.session.HazelcastSessionConfiguration
import java.time.Duration
import org.nowstart.gateway.audit.HazelcastAuditEventRepository
import org.nowstart.gateway.session.HazelcastReactiveSessionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.config.annotation.web.server.EnableSpringWebSession

@Configuration(proxyBeanMethods = false)
@EnableSpringWebSession
class HazelcastConfiguration {
    @Bean
    fun gatewayHazelcastConfig(
        @Value("\${app.cluster.name:gateway}") name: String,
        @Value("\${app.cluster.members:127.0.0.1}") members: List<String>,
        @Value("\${app.cluster.interfaces:127.0.0.1}") interfaces: List<String>,
        @Value("\${app.cluster.port:5701}") port: Int,
    ): Config {
        val config = Config().setClusterName(name)
        config.setProperty("hazelcast.shutdownhook.policy", "GRACEFUL")
        config.setProperty("hazelcast.graceful.shutdown.max.wait", "90")
        val network = config.networkConfig.setPort(port).setPortAutoIncrement(false)
        network.interfaces.setEnabled(true).setInterfaces(interfaces)
        network.join.autoDetectionConfig.setEnabled(false)
        network.join.multicastConfig.setEnabled(false)
        network.join.tcpIpConfig.setEnabled(true).setMembers(members)
        config.addMapConfig(MapConfig(SESSION_MAP).setBackupCount(1).setAsyncBackupCount(0))
        config.addMapConfig(
            MapConfig(HazelcastAuditEventRepository.MAP_NAME)
                .setBackupCount(1)
                .setAsyncBackupCount(0),
        )
        HazelcastSessionConfiguration.applySerializationConfig(config)
        return config
    }

    @Bean
    fun hazelcastSessionRepository(
        hazelcast: HazelcastInstance,
        publisher: ApplicationEventPublisher,
        @Value("\${spring.session.timeout:30m}") timeout: Duration,
    ): HazelcastIndexedSessionRepository =
        HazelcastIndexedSessionRepository(hazelcast)
            .setSessionMapName(SESSION_MAP)
            .setDefaultMaxInactiveInterval(timeout)
            .setApplicationEventPublisher(publisher)

    @Bean
    fun reactiveSessionRepository(repository: HazelcastIndexedSessionRepository) =
        HazelcastReactiveSessionRepository(repository)

    companion object {
        const val SESSION_MAP = "nowstart:gateway:sessions"
    }
}
