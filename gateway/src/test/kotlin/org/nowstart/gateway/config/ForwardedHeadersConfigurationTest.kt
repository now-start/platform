package org.nowstart.gateway.config

import java.net.InetSocketAddress
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.filter.headers.ForwardedHeadersFilter
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter
import org.springframework.cloud.gateway.filter.headers.RemoveForwardedHeadersFilter
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter
import org.springframework.context.ApplicationContext
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.util.ForwardedHeaderUtils

@ActiveProfiles("test")
@SpringBootTest
class ForwardedHeadersConfigurationTest {

    @field:MockitoBean
    private lateinit var clientRegistrationRepository: ReactiveClientRegistrationRepository

    @field:Autowired private lateinit var context: ApplicationContext

    @field:Autowired private lateinit var filters: List<HttpHeadersFilter>

    @Test
    fun usesBuiltInFiltersWithoutRewritingForwardedFor() {
        assertThat(context.getBeansOfType(ForwardedHeadersFilter::class.java)).isEmpty()
        assertThat(context.getBeansOfType(RemoveForwardedHeadersFilter::class.java)).hasSize(1)
        assertThat(context.getBean(XForwardedHeadersFilter::class.java).isForEnabled()).isFalse()
        assertThat(context.getBean(XForwardedHeadersFilter::class.java).isPortEnabled()).isFalse()
    }

    private val URI: URI = java.net.URI.create("http://spring.example.com/barosteel/login")

    @ParameterizedTest
    @ValueSource(strings = ["203.0.113.40", "2001:db8::40", "203.0.113.40, 10.0.0.2"])
    fun preservesClientIpThroughGatewayFiltersAndSpringRemoteAddressParsing(clientIp: String) {
        val request =
            MockServerHttpRequest.get(URI.toString())
                .remoteAddress(InetSocketAddress("10.0.0.7", 45678))
                .header("Host", "spring.example.com")
                .header("X-Forwarded-For", clientIp)
                .header("X-Forwarded-Proto", "https")
                .build()
        val result: HttpHeaders = filter(MockServerWebExchange.from(request))

        assertThat(result.getFirst("X-Forwarded-For")).isEqualTo(clientIp)
        assertThat(result.containsHeader("Forwarded")).isFalse()
        val remote = ForwardedHeaderUtils.parseForwardedFor(URI, result, null)
        assertThat(remote).isNotNull()
        assertThat(requireNotNull(remote).hostString.replace("[", "").replace("]", ""))
            .isEqualTo(clientIp.split(",")[0])
        assertThat(
                ForwardedHeaderUtils.adaptFromForwardedHeaders(URI, result).build().toUriString(),
            )
            .isEqualTo("https://spring.example.com/barosteel/login")
    }

    @Test
    fun doesNotRestoreSpoofedHeadersFromUntrustedConnection() {
        val request =
            MockServerHttpRequest.get(URI.toString())
                .remoteAddress(InetSocketAddress("198.51.100.8", 45678))
                .header("X-Forwarded-For", "203.0.113.40")
                .header("Forwarded", "for=203.0.113.40;proto=https")
                .build()
        val result: HttpHeaders = filter(MockServerWebExchange.from(request))

        assertThat(result.containsHeader("X-Forwarded-For")).isFalse()
        assertThat(result.containsHeader("Forwarded")).isFalse()
    }

    @Test
    fun leavesRemoteAddressUnmodifiedWhenClientHeaderIsMissing() {
        val request =
            MockServerHttpRequest.get(URI.toString())
                .remoteAddress(InetSocketAddress("10.0.0.7", 45678))
                .build()
        val result: HttpHeaders = filter(MockServerWebExchange.from(request))

        assertThat(result.containsHeader("X-Forwarded-For")).isFalse()
        assertThat(result.containsHeader("Forwarded")).isFalse()
        assertThat(ForwardedHeaderUtils.parseForwardedFor(URI, result, null)).isNull()
    }

    private fun filter(exchange: MockServerWebExchange): HttpHeaders {
        return HttpHeadersFilter.filterRequest(filters, exchange)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun productionForwardingProperties(registry: DynamicPropertyRegistry) {
            val yaml = YamlPropertiesFactoryBean()
            yaml.setResources(
                FileSystemResource("../config/src/main/resources/config/gateway/gateway.yaml"),
            )
            val properties = requireNotNull(yaml.getObject())
            for (key in
                listOf(
                    "server.forward-headers-strategy",
                    "spring.cloud.gateway.server.webflux.trusted-proxies",
                    "spring.cloud.gateway.server.webflux.forwarded.enabled",
                    "spring.cloud.gateway.server.webflux.x-forwarded.for-enabled",
                    "spring.cloud.gateway.server.webflux.x-forwarded.port-enabled",
                )) {
                registry.add(key, { properties.getProperty(key) })
            }
        }
    }
}
