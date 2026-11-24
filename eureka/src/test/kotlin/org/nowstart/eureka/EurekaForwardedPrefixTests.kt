package org.nowstart.eureka

import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        [
            "server.forward-headers-strategy=framework",
            "server.request.header.forwarded.use-prefix=true",
            "spring.cloud.config.enabled=false",
            "spring.boot.admin.client.enabled=false",
            "otel.sdk.disabled=true",
            "eureka.client.register-with-eureka=false",
            "eureka.client.fetch-registry=false",
            "eureka.server.wait-time-in-ms-when-sync-empty=0",
        ],
)
class EurekaForwardedPrefixTests {

    @LocalServerPort private var port: Int = 0
    private val client = HttpClient.newHttpClient()

    @Test
    fun dashboardLinksAndAssetsWorkBehindTheGateway() {
        val dashboard = get("/", "text/html", forwarded = true)
        assertThat(dashboard.statusCode()).isEqualTo(200)
        val html = dashboard.body()
        assertThat(html).contains("""<base href="/eureka/">""", """href="/eureka/lastn"""")
        val base = URI.create("/eureka/")

        for ((asset, mediaType) in
            mapOf("eureka/css/wro.css" to "text/css", "eureka/js/wro.js" to "text/javascript")) {
            assertThat(html).contains("\"$asset\"")
            val publicPath = base.resolve(asset).path
            // The discovery route strips its service ID before forwarding to Eureka.
            val response = get(publicPath.removePrefix("/eureka"), mediaType, forwarded = true)
            assertThat(response.statusCode()).describedAs(publicPath).isEqualTo(200)
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith(mediaType)
        }

        val lastn = get("/lastn", "text/html", forwarded = true)
        assertThat(lastn.statusCode()).isEqualTo(200)
        assertThat(lastn.body()).contains("""<base href="/eureka/">""")
    }

    @Test
    fun registryWorksForDirectClientsAndThroughTheGateway() {
        for (forwarded in listOf(false, true)) {
            val response = get("/eureka/apps", "application/json", forwarded)
            assertThat(response.statusCode()).describedAs("forwarded=%s", forwarded).isEqualTo(200)
            assertThat(response.body()).contains("\"applications\"")
        }
    }

    private fun get(path: String, accept: String, forwarded: Boolean): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", accept)
        if (forwarded) {
            request
                .header("X-Forwarded-Prefix", "/eureka")
                .header("X-Forwarded-Host", "gateway.example")
                .header("X-Forwarded-Proto", "https")
        }
        val response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
        val expectedOrigin = if (forwarded) "https://gateway.example" else "http://127.0.0.1"
        assertThat(response.headers().firstValue("X-Test-Request-Origin")).contains(expectedOrigin)
        return response
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ObservedOrigin {
        @Bean
        fun originProbe(): FilterRegistrationBean<Filter> {
            val probe = Filter { request, response, chain ->
                (response as HttpServletResponse).setHeader(
                    "X-Test-Request-Origin",
                    "${request.scheme}://${request.serverName}",
                )
                chain.doFilter(request, response)
            }
            return FilterRegistrationBean(probe).apply { order = Ordered.HIGHEST_PRECEDENCE + 1 }
        }
    }
}
