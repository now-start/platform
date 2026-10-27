package org.nowstart.eureka

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Hashtable
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext
import org.slf4j.LoggerFactory

class SwarmPeerResolver(
    private val service: String,
    private val port: Int,
    private val networkCidr: String,
    private val lookup: AddressLookup = AddressLookup(::lookupAddresses),
    private val localAddresses: LocalAddresses = LocalAddresses(::localAddresses),
) {
    private val localAddress =
        selectLocalAddress(
            networkCidr,
            localAddresses.get().map(InetAddress::getByName),
        )
    private var lastKnownPeers: List<String> = emptyList()

    @Synchronized
    fun resolve(): List<String> {
        try {
            val addresses =
                matching(
                        networkCidr,
                        lookup.find(service).map(InetAddress::getByName),
                    )
                    .map { it.hostAddress }
                    .toSet()
            // DNS 응답이 비어 있다고 해서 모든 피어가 떠난 것은 아니다.
            if (addresses.isEmpty())
                throw NamingException("No task addresses returned for $service")
            if (localAddress !in addresses) {
                throw NamingException("Task DNS does not include this container: $service")
            }
            lastKnownPeers =
                addresses.filter { it != localAddress }.sorted().map { "http://$it:$port/eureka/" }
        } catch (ex: NamingException) {
            log.warn(
                "Cannot refresh Swarm peers from {}; retaining previous peers: {}",
                service,
                ex.message,
            )
        } catch (ex: SocketException) {
            log.warn(
                "Cannot refresh Swarm peers from {}; retaining previous peers: {}",
                service,
                ex.message,
            )
        }
        return lastKnownPeers
    }

    fun localUrl(): String = "http://127.0.0.1:$port/eureka/"

    fun interface AddressLookup {
        fun find(service: String): Set<String>
    }

    fun interface LocalAddresses {
        fun get(): Set<String>
    }

    companion object {
        private fun selectLocalAddress(cidr: String, addresses: List<InetAddress>): String {
            val candidates = matching(cidr, addresses)
            check(candidates.size == 1) {
                "Expected one local IPv4 in $cidr; candidates=$candidates"
            }
            return candidates.single().hostAddress
        }

        private fun matching(cidr: String, addresses: List<InetAddress>): List<Inet4Address> {
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
            return candidates
        }

        private val log = LoggerFactory.getLogger(SwarmPeerResolver::class.java)

        private fun lookupAddresses(service: String): Set<String> {
            // InetAddress의 JVM 공용 캐시가 태스크 변경을 가릴 수 있으므로 Docker DNS를 직접 조회한다.
            val environment = Hashtable<String, String>()
            environment["java.naming.factory.initial"] = "com.sun.jndi.dns.DnsContextFactory"
            environment["java.naming.provider.url"] = "dns:"
            environment["com.sun.jndi.dns.timeout.initial"] = "1000"
            environment["com.sun.jndi.dns.timeout.retries"] = "1"
            val context = InitialDirContext(environment)
            try {
                val attribute =
                    context.getAttributes(service, arrayOf("A")).get("A") ?: return emptySet()
                return (0 until attribute.size()).map { attribute.get(it).toString() }.toSortedSet()
            } finally {
                context.close()
            }
        }

        private fun localAddresses(): Set<String> =
            NetworkInterface.networkInterfaces().use { interfaces ->
                interfaces.flatMap { it.inetAddresses() }.map { it.hostAddress }.toList().toSet()
            }
    }
}
