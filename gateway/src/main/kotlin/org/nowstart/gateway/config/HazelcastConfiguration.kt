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
    fun hazelcastConfig(
        @Value("\${app.cluster.name:\${spring.application.name}}") name: String,
        @Value("\${app.cluster.members}") members: List<String>,
        @Value("\${app.cluster.port:5701}") port: Int,
        @Value("\${app.cluster.local-address:}") localAddress: String = "",
        @Value("\${app.cluster.network-reference:}") networkReference: String = "",
    ): Config {
        val config = Config().setClusterName(name)
        if (localAddress.isNotBlank()) {
            val address = ClusterAddressSelector.resolve(localAddress, networkReference)
            config.setProperty("hazelcast.local.localAddress", address.hostAddress)
            // 다른 overlay(예: 공개용 edge_public)에서 클러스터 포트에 닿지 않도록 선택한 주소에만 바인딩한다.
            config.setProperty("hazelcast.socket.bind.any", "false")
        }
        config.setProperty("hazelcast.shutdownhook.policy", "GRACEFUL")
        config.setProperty("hazelcast.graceful.shutdown.max.wait", "90")
        val network = config.networkConfig.setPort(port).setPortAutoIncrement(false)
        network.join.autoDetectionConfig.setEnabled(false)
        network.join.multicastConfig.setEnabled(false)
        network.join.tcpIpConfig.setEnabled(true).setMembers(members)
        config.addMapConfig(createSharedMap(SESSION_MAP))
        config.addMapConfig(
            createSharedMap(HazelcastAuditEventRepository.MAP_NAME),
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

    private fun createSharedMap(name: String): MapConfig =
        MapConfig(name).setBackupCount(1).setAsyncBackupCount(0)

    companion object {
        const val SESSION_MAP = "nowstart:gateway:sessions"
    }
}
