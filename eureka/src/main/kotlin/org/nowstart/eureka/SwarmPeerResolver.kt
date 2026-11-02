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
    private val lookup: AddressLookup = AddressLookup(::lookupAddresses),
    private val localAddresses: LocalAddresses = LocalAddresses(::localAddresses),
    private val pause: (Long) -> Unit = Thread::sleep,
    private val bootstrapAddresses: LocalAddresses = LocalAddresses(::hostnameAddresses),
) {
    val localAddress = selectLocalAddress()
    private var lastKnownPeers: List<String> = emptyList()

    @Synchronized
    fun resolve(): List<String> {
        try {
            val addresses = taskAddresses()
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

    private fun taskAddresses(): Set<String> =
        lookup
            .find(service)
            .map(InetAddress::getByName)
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .toSet()

    private fun selectLocalAddress(): String {
        var failure: Exception? = null
        repeat(10) { attempt ->
            try {
                val candidates = taskAddresses().intersect(localAddresses.get())
                if (candidates.size == 1) return candidates.single()
                failure =
                    NamingException(
                        "Expected one local task IPv4 for $service; candidates=$candidates",
                    )
            } catch (ex: NamingException) {
                failure = ex
            } catch (ex: SocketException) {
                failure = ex
            }
            if (attempt < 9) {
                try {
                    pause(1000)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException(
                        "Interrupted while discovering local task address for $service",
                        ex,
                    )
                }
            }
        }
        // Swarm does not publish unhealthy tasks in service DNS. Docker's own hostname
        // is available in /etc/hosts before health checks pass on the single overlay.
        val bootstrap =
            bootstrapAddresses
                .get()
                .map(InetAddress::getByName)
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .map { it.hostAddress }
                .toSet()
                .intersect(localAddresses.get())
        if (bootstrap.size == 1) {
            log.warn(
                "Task DNS is not ready for {}; bootstrapping with Docker hostname address {}",
                service,
                bootstrap.single(),
            )
            return bootstrap.single()
        }
        throw IllegalStateException(
            "Cannot discover local task address for $service after 10 attempts",
            failure,
        )
    }

    fun localUrl(): String = "http://127.0.0.1:$port/eureka/"

    fun interface AddressLookup {
        fun find(service: String): Set<String>
    }

    fun interface LocalAddresses {
        fun get(): Set<String>
    }

    companion object {
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

        private fun hostnameAddresses(): Set<String> =
            InetAddress.getAllByName(
                    System.getenv("HOSTNAME") ?: InetAddress.getLocalHost().hostName,
                )
                .map { it.hostAddress }
                .toSet()

        private fun localAddresses(): Set<String> =
            NetworkInterface.networkInterfaces().use { interfaces ->
                interfaces.flatMap { it.inetAddresses() }.map { it.hostAddress }.toList().toSet()
            }
    }
}
