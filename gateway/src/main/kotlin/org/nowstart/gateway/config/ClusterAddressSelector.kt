package org.nowstart.gateway.config

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

/**
 * Hazelcast가 광고·바인딩할 자기 IPv4를 고른다.
 *
 * 컨테이너가 overlay 여러 개에 연결되면 Docker HOSTNAME이 네트워크마다 하나씩 IPv4로 풀린다. Swarm은 healthy 이전 task를 DNS에 싣지
 * 않으므로 자기 이름 대신, 이미 기동한 클러스터 네트워크의 기준 호스트(예: Config Server)와 같은 대역에 있는 후보 하나를 고른다.
 */
internal object ClusterAddressSelector {
    private const val IPV4_BITS = 32

    fun resolve(localAddress: String, networkReference: String): Inet4Address =
        select(
            candidates = ipv4Addresses(localAddress),
            references = {
                if (networkReference.isBlank()) emptyList() else ipv4Addresses(networkReference)
            },
            prefixLength = ::interfacePrefixLength,
        )

    fun select(
        candidates: List<Inet4Address>,
        references: () -> List<Inet4Address>,
        prefixLength: (Inet4Address) -> Int?,
    ): Inet4Address {
        val distinct = candidates.distinct()
        check(distinct.isNotEmpty()) {
            "app.cluster.local-address did not resolve to an IPv4 address"
        }
        if (distinct.size == 1) return distinct.single()

        val referenceAddresses = references().distinct()
        check(referenceAddresses.isNotEmpty()) {
            "app.cluster.local-address resolved to multiple IPv4 addresses ${format(distinct)}; " +
                "set app.cluster.network-reference to a host on the cluster network"
        }
        val matches = distinct.filter { candidate ->
            val prefix = prefixLength(candidate)
            prefix != null &&
                prefix in 1..IPV4_BITS &&
                referenceAddresses.any { sameNetwork(candidate, it, prefix) }
        }
        check(matches.size == 1) {
            "app.cluster.network-reference ${format(referenceAddresses)} must share a network with exactly one " +
                "local address of ${format(distinct)}, but matched ${format(matches)}"
        }
        return matches.single()
    }

    private fun sameNetwork(left: Inet4Address, right: Inet4Address, prefix: Int): Boolean {
        val mask = -1 shl (IPV4_BITS - prefix)
        return (left.toInt() and mask) == (right.toInt() and mask)
    }

    private fun Inet4Address.toInt(): Int = ByteBuffer.wrap(address).int

    private fun ipv4Addresses(host: String): List<Inet4Address> =
        InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()

    private fun interfacePrefixLength(address: Inet4Address): Int? =
        NetworkInterface.getByInetAddress(address)
            ?.interfaceAddresses
            ?.firstOrNull { it.address == address }
            ?.networkPrefixLength
            ?.toInt()

    private fun format(addresses: List<Inet4Address>): String =
        addresses.map { it.hostAddress }.toString()
}
