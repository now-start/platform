package org.nowstart.gateway.config

import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory
import org.springframework.cloud.gateway.support.TimeoutException
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono

class GatewayRetryConfigurationTest {
    private fun createFilter(): GatewayFilter {
        val environment = StandardEnvironment()
        YamlPropertySourceLoader()
            .load(
                "gateway-config",
                FileSystemResource("../config/src/main/resources/config/gateway/gateway.yaml"),
            )
            .forEach { environment.propertySources.addFirst(it) }
        val properties =
            Binder.get(environment)
                .bind("spring.cloud.gateway.server.webflux", GatewayProperties::class.java)
                .get()
        val definition = properties.defaultFilters.single { it.name == "Retry" }
        assertThat(definition.args).isEmpty()
        val factory = RetryGatewayFilterFactory()
        factory.setApplicationEventPublisher { _ -> }
        val config = RetryGatewayFilterFactory.RetryConfig()
        config.setRouteId("test")
        return factory.apply(config)
    }

    @Test
    fun retriesServerErrorsThreeTimesOnlyForGet() {
        for (method in
            listOf(
                HttpMethod.GET,
                HttpMethod.HEAD,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.PATCH,
                HttpMethod.DELETE,
                HttpMethod.OPTIONS,
            )) {
            for (status in
                listOf(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    HttpStatus.BAD_GATEWAY,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    HttpStatus.GATEWAY_TIMEOUT,
                    HttpStatus.NOT_IMPLEMENTED,
                    HttpStatus.BAD_REQUEST,
                )) {
                val attempts = AtomicInteger()
                createFilter()
                    .filter(
                        MockServerWebExchange.from(
                            MockServerHttpRequest.method(method, "/test").build(),
                        ),
                        GatewayFilterChain { exchange ->
                            Mono.defer {
                                attempts.incrementAndGet()
                                exchange.response.statusCode = status
                                Mono.empty()
                            }
                        },
                    )
                    .block(Duration.ofSeconds(5))
                assertThat(attempts.get())
                    .describedAs("%s %s", method, status)
                    .isEqualTo(if (method == HttpMethod.GET && status.is5xxServerError) 4 else 1)
            }
        }
    }

    @Test
    fun retriesDefaultExceptionsOnlyForGet() {
        for (method in
            listOf(
                HttpMethod.GET,
                HttpMethod.HEAD,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.PATCH,
                HttpMethod.DELETE,
                HttpMethod.OPTIONS,
            )) {
            for (failure in
                listOf(
                    IOException("connection reset"),
                    TimeoutException("timeout"),
                    IllegalStateException("application error"),
                )) {
                val attempts = AtomicInteger()
                assertThatThrownBy {
                        createFilter()
                            .filter(
                                MockServerWebExchange.from(
                                    MockServerHttpRequest.method(method, "/test").build(),
                                ),
                                GatewayFilterChain {
                                    Mono.defer {
                                        attempts.incrementAndGet()
                                        Mono.error(failure)
                                    }
                                },
                            )
                            .block(Duration.ofSeconds(5))
                    }
                    .isInstanceOf(Exception::class.java)
                val retries = method == HttpMethod.GET && failure !is IllegalStateException
                assertThat(attempts.get())
                    .describedAs("%s %s", method, failure.javaClass.simpleName)
                    .isEqualTo(if (retries) 4 else 1)
            }
        }
    }
}
