package org.nowstart.gateway.config

import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.spring.session.HazelcastIndexedSessionRepository
import com.hazelcast.spring.session.HazelcastSessionConfiguration
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
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
        @Value("\${app.cluster.network-cidr}") networkCidr: String,
        @Value("\${app.cluster.port:5701}") port: Int,
    ): Config {
        val config = Config().setClusterName(name)
        NetworkInterface.networkInterfaces().use { interfaces ->
            val addresses = interfaces.flatMap { it.inetAddresses() }.toList()
            config.setProperty(
                "hazelcast.local.localAddress",
                selectLocalAddress(networkCidr, addresses),
            )
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

        fun selectLocalAddress(cidr: String, addresses: List<InetAddress>): String {
            val parts = cidr.split('/')
            require(parts.size == 2) { "Expected IPv4 CIDR: $cidr" }
            val octets = parts[0].split('.').map { it.toIntOrNull() }
            val prefix = parts[1].toIntOrNull()
            require(
                octets.size == 4 &&
                    octets.all { it != null && it in 0..255 } &&
                    prefix != null &&
                    prefix in 1..32,
            ) {
                "Expected IPv4 CIDR: $cidr"
            }
            val subnet = octets.map { it!!.toByte() }.toByteArray()
            val candidates =
                addresses
                    .filterIsInstance<Inet4Address>()
                    .filter { address ->
                        (0 until prefix).all { bit ->
                            val mask = 1 shl (7 - bit % 8)
                            (address.address[bit / 8].toInt() and mask) ==
                                (subnet[bit / 8].toInt() and mask)
                        }
                    }
                    .distinct()
            check(candidates.size == 1) {
                "Expected one local IPv4 in $cidr; candidates=$candidates"
            }
            return candidates.single().hostAddress
        }
    }
}
