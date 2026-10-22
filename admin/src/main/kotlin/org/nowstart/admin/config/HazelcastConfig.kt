package org.nowstart.admin.config

import com.hazelcast.config.Config
import com.hazelcast.config.InMemoryFormat
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MergePolicyConfig
import de.codecentric.boot.admin.server.config.AdminServerHazelcastAutoConfiguration.DEFAULT_NAME_EVENT_STORE_MAP
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class HazelcastConfig {
    @Bean
    fun adminHazelcastConfig(
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
        val join = network.join
        join.autoDetectionConfig.setEnabled(false)
        join.multicastConfig.setEnabled(false)
        join.tcpIpConfig.setEnabled(true).setMembers(members)
        config.addMapConfig(sharedMap("default"))
        config.addMapConfig(
            sharedMap(DEFAULT_NAME_EVENT_STORE_MAP).setInMemoryFormat(InMemoryFormat.OBJECT),
        )
        return config
    }

    private fun sharedMap(name: String): MapConfig =
        MapConfig(name)
            .setBackupCount(1)
            .setAsyncBackupCount(0)
            .setMergePolicyConfig(MergePolicyConfig("PutIfAbsentMergePolicy", 100))

    companion object {
        fun selectLocalAddress(cidr: String, addresses: List<InetAddress>): String {
            val parts = cidr.split('/')
            require(parts.size == 2 && parts[0].matches(Regex("[0-9.]+"))) {
                "Expected IPv4 CIDR: $cidr"
            }
            val subnet = InetAddress.getByName(parts[0])
            val prefix = parts[1].toInt()
            require(subnet is Inet4Address && prefix in 1..32) { "Expected IPv4 CIDR: $cidr" }
            val candidates =
                addresses
                    .filterIsInstance<Inet4Address>()
                    .filter { address ->
                        (0 until prefix).all { bit ->
                            val mask = 1 shl (7 - bit % 8)
                            (address.address[bit / 8].toInt() and mask) ==
                                (subnet.address[bit / 8].toInt() and mask)
                        }
                    }
                    .distinct()
            check(candidates.size == 1) {
                "Expected one local IPv4 in $cidr; candidates=$candidates"
            }
            return candidates.first().hostAddress
        }
    }
}
