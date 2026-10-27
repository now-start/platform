package org.nowstart.eureka

import com.netflix.appinfo.ApplicationInfoManager
import com.netflix.eureka.cluster.PeerEurekaNodes
import com.netflix.eureka.registry.PeerAwareInstanceRegistry
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@SpringBootTest(
    classes = [EurekaServiceApplication::class, SwarmBootstrapTests.TestPeers::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        [
            "spring.main.allow-bean-definition-overriding=true",
            "spring.config.import=",
            "spring.cloud.config.enabled=false",
            "spring.boot.admin.client.enabled=false",
            "otel.sdk.disabled=true",
            "eureka.swarm.enabled=true",
            "eureka.instance.ip-address=10.0.10.104",
            "eureka.instance.hostname=wrong-network-container",
            "eureka.instance.prefer-ip-address=false",
            "management.server.port=0",
            "eureka.client.register-with-eureka=false",
            "eureka.client.fetch-registry=true",
            "eureka.server.registry-sync-retries=1",
        ],
)
class SwarmBootstrapTests {

    @field:Autowired lateinit var registry: PeerAwareInstanceRegistry

    @field:Autowired lateinit var peers: PeerEurekaNodes

    @field:Autowired lateinit var applicationInfoManager: ApplicationInfoManager

    @Test
    fun advertisesSelectedNetworkAfterBindingAndMatchesPeerIdentity() {
        val info = applicationInfoManager.info
        assertThat(info.ipAddr).isEqualTo("127.0.0.2")
        assertThat(info.hostName).isEqualTo("127.0.0.2")
        assertThat(URI(info.statusPageUrl).host).isEqualTo("127.0.0.2")
        assertThat(URI(info.healthCheckUrl).host).isEqualTo("127.0.0.2")
        assertThat(URI(info.homePageUrl).host).isEqualTo("127.0.0.2")
        assertThat(peers.isInstanceURL("http://127.0.0.2:8761/eureka/", info)).isTrue()
        assertThat(peers.isInstanceURL("http://10.0.10.104:8761/eureka/", info)).isFalse()
        assertThat(peers.isThisMyUrl("http://127.0.0.2:8761/eureka/")).isTrue()
    }

    @Test
    fun startsServerWithExistingRegistrationsFetchedFromDiscoveredPeer() {
        assertThat(peers.getPeerNodesView()).hasSize(1)
        assertThat(peers.getPeerNodesView().first().getServiceUrl())
            .isEqualTo("http://127.0.0.1:" + PEER.getAddress().getPort() + "/eureka/")
        assertThat(registry.getApplication("EXISTING")).isNotNull()
        assertThat(registry.getApplication("EXISTING").getByInstanceId("existing-1")).isNotNull()
    }

    @Test
    fun replicatesRegistrationToDiscoveredPeerOverHttp() {
        registry.register(registry.getApplication("EXISTING").getByInstanceId("existing-1"), false)
        assertThat(REPLICATIONS.poll(10, TimeUnit.SECONDS)).contains("existing-1", "Register")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestPeers {
        @Bean
        fun swarmPeerResolver(): SwarmPeerResolver {
            return SwarmPeerResolver(
                "tasks.eureka",
                PEER.getAddress().getPort(),
                "127.0.0.0/8",
                { name -> setOf("127.0.0.2", "127.0.0.1") },
                { setOf("10.0.10.104", "127.0.0.2") },
            )
        }
    }

    companion object {
        private val REPLICATIONS: LinkedBlockingQueue<String> = LinkedBlockingQueue()
        private val PEER: HttpServer = startPeer()

        @JvmStatic
        @AfterAll
        fun stopPeer() {
            PEER.stop(0)
        }

        private fun startPeer(): HttpServer {
            try {
                val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val body =
                    """
                    {"applications":{"versions__delta":"1","apps__hashcode":"UP_1_",
                      "application":[{"name":"EXISTING","instance":[{
                        "instanceId":"existing-1","hostName":"existing","app":"EXISTING",
                        "ipAddr":"10.0.0.9","status":"UP","overriddenStatus":"UNKNOWN",
                        "port":{"${'$'}":8080,"@enabled":"true"},
                        "securePort":{"${'$'}":443,"@enabled":"false"},
                        "dataCenterInfo":{"@class":"com.netflix.appinfo.InstanceInfo${'$'}DefaultDataCenterInfo","name":"MyOwn"},
                        "leaseInfo":{"renewalIntervalInSecs":30,"durationInSecs":90},
                        "metadata":{},"vipAddress":"existing","secureVipAddress":"existing",
                        "isCoordinatingDiscoveryServer":"false",
                        "lastUpdatedTimestamp":"1","lastDirtyTimestamp":"1","actionType":"ADDED"
                      }]}]}}
                    """
                        .toByteArray(StandardCharsets.UTF_8)
                server.createContext(
                    "/eureka/apps",
                    { exchange ->
                        exchange.getResponseHeaders().set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        try {
                            exchange.responseBody.use { it.write(body) }
                        } finally {
                            exchange.close()
                        }
                    },
                )
                server.createContext(
                    "/eureka/peerreplication/batch/",
                    { exchange ->
                        var input = exchange.getRequestBody()
                        if (
                            "gzip"
                                .equals(
                                    exchange.requestHeaders.getFirst("Content-Encoding"),
                                    ignoreCase = true,
                                )
                        ) {
                            input = GZIPInputStream(input)
                        }
                        REPLICATIONS.add(String(input.readAllBytes(), StandardCharsets.UTF_8))
                        input.close()
                        val response =
                            "{\"replicationList\":[{\"statusCode\":200}]}"
                                .toByteArray(StandardCharsets.UTF_8)
                        exchange.getResponseHeaders().set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, response.size.toLong())
                        try {
                            exchange.responseBody.use { it.write(response) }
                        } finally {
                            exchange.close()
                        }
                    },
                )
                server.start()
                return server
            } catch (ex: IOException) {
                throw ExceptionInInitializerError(ex)
            }
        }
    }
}
