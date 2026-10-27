package org.nowstart.eureka

import com.netflix.appinfo.ApplicationInfoManager
import com.netflix.discovery.EurekaClientConfig
import com.netflix.eureka.EurekaServerConfig
import com.netflix.eureka.cluster.PeerEurekaNodes
import com.netflix.eureka.registry.PeerAwareInstanceRegistry
import com.netflix.eureka.resources.ServerCodecs
import java.net.URI
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["eureka.swarm.enabled"], havingValue = "true")
class SwarmDiscoveryConfiguration {
    companion object {
        @Bean
        @JvmStatic
        fun swarmInstanceAddress(resolver: ObjectProvider<SwarmPeerResolver>): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                    if (bean is EurekaInstanceConfigBean) {
                        // 설정 바인딩 이후, ApplicationInfoManager가 등록 정보를 만들기 전에 적용한다.
                        val address = resolver.getObject().localAddress
                        bean.ipAddress = address
                        bean.hostname = address
                        bean.isPreferIpAddress = true
                        // 자동 설정이 이미 생성한 관리 URL도 같은 네트워크로 광고한다.
                        fun onSelectedNetwork(url: String?): String? = url?.let {
                            val uri = URI(it)
                            URI(
                                    uri.scheme,
                                    uri.userInfo,
                                    address,
                                    uri.port,
                                    uri.path,
                                    uri.query,
                                    uri.fragment,
                                )
                                .toString()
                        }
                        bean.statusPageUrl = onSelectedNetwork(bean.statusPageUrl)
                        bean.healthCheckUrl = onSelectedNetwork(bean.healthCheckUrl)
                        bean.secureHealthCheckUrl = onSelectedNetwork(bean.secureHealthCheckUrl)
                        bean.homePageUrl = onSelectedNetwork(bean.homePageUrl)
                    }
                    return bean
                }
            }
    }

    @Bean
    fun swarmPeerResolver(
        @Value("\${eureka.swarm.service:tasks.eureka}") service: String,
        @Value("\${server.port:8761}") port: Int,
        @Value("\${eureka.swarm.network-cidr}") networkCidr: String,
    ) = SwarmPeerResolver(service, port, networkCidr)

    @Bean
    @ConfigurationProperties("eureka.client")
    fun eurekaClientConfigBean(resolver: SwarmPeerResolver): EurekaClientConfigBean =
        object : EurekaClientConfigBean() {
            override fun getEurekaServerServiceUrls(zone: String): List<String> {
                val peers = resolver.resolve()
                // 단일 인스턴스도 시작할 수 있어야 한다. 다른 피어가 있으면 초기 동기화에 VIP를 사용하지 않는다.
                // VIP를 거치면 요청이 자기 자신에게 돌아올 수 있다.
                return peers.ifEmpty { listOf(resolver.localUrl()) }
            }
        }

    @Bean(destroyMethod = "")
    fun peerEurekaNodes(
        registry: PeerAwareInstanceRegistry,
        serverConfig: EurekaServerConfig,
        clientConfig: EurekaClientConfig,
        codecs: ServerCodecs,
        applicationInfoManager: ApplicationInfoManager,
        resolver: SwarmPeerResolver,
    ): PeerEurekaNodes =
        SwarmPeerEurekaNodes(
            registry,
            serverConfig,
            clientConfig,
            codecs,
            applicationInfoManager,
            resolver,
        )
}
