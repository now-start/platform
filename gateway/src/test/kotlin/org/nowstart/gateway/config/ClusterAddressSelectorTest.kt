package org.nowstart.gateway.config

import java.net.Inet4Address
import java.net.InetAddress
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClusterAddressSelectorTest {
    private val platform = ipv4("10.0.1.9")
    private val edge = ipv4("10.0.2.9")
    private val configServer = ipv4("10.0.1.6")
    private val overlayPrefix: (Inet4Address) -> Int? = { 24 }

    @Test
    fun selectsSingleCandidateWithoutResolvingReference() {
        var referenceResolved = false

        val selected =
            ClusterAddressSelector.select(
                candidates = listOf(platform),
                references = {
                    referenceResolved = true
                    emptyList()
                },
                prefixLength = overlayPrefix,
            )

        assertThat(selected).isEqualTo(platform)
        assertThat(referenceResolved).isFalse()
    }

    @Test
    fun selectsCandidateOnSameNetworkAsReference() {
        val selected =
            ClusterAddressSelector.select(
                candidates = listOf(edge, platform),
                references = { listOf(configServer) },
                prefixLength = overlayPrefix,
            )

        assertThat(selected).isEqualTo(platform)
    }

    @Test
    fun rejectsMultipleCandidatesWithoutReference() {
        assertThatThrownBy {
                ClusterAddressSelector.select(
                    candidates = listOf(edge, platform),
                    references = { emptyList() },
                    prefixLength = overlayPrefix,
                )
            }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("app.cluster.network-reference")
    }

    @Test
    fun rejectsReferenceOutsideEveryCandidateNetwork() {
        assertThatThrownBy {
                ClusterAddressSelector.select(
                    candidates = listOf(edge, platform),
                    references = { listOf(ipv4("10.0.3.6")) },
                    prefixLength = overlayPrefix,
                )
            }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("10.0.3.6")
    }

    @Test
    fun rejectsReferenceMatchingSeveralCandidates() {
        assertThatThrownBy {
                ClusterAddressSelector.select(
                    candidates = listOf(edge, platform),
                    references = { listOf(configServer) },
                    prefixLength = { 16 },
                )
            }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("10.0.1.9")
            .hasMessageContaining("10.0.2.9")
    }

    @Test
    fun ignoresCandidatesWithUnknownOrInvalidPrefix() {
        val selected =
            ClusterAddressSelector.select(
                candidates = listOf(edge, platform),
                references = { listOf(configServer) },
                prefixLength = { if (it == platform) 24 else 0 },
            )

        assertThat(selected).isEqualTo(platform)

        assertThatThrownBy {
                ClusterAddressSelector.select(
                    candidates = listOf(edge, platform),
                    references = { listOf(configServer) },
                    prefixLength = { null },
                )
            }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun rejectsMissingCandidates() {
        assertThatThrownBy {
                ClusterAddressSelector.select(
                    candidates = emptyList(),
                    references = { listOf(configServer) },
                    prefixLength = overlayPrefix,
                )
            }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address
}
