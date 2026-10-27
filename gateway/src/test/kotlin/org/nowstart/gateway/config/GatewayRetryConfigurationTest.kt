package org.nowstart.gateway.config

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.event.EnableBodyCachingEvent
import org.springframework.cloud.gateway.filter.AdaptCachedBodyGlobalFilter
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.RemoveCachedBodyFilter
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono

class GatewayRetryConfigurationTest {
    private fun createFilter(
        cache: AdaptCachedBodyGlobalFilter = AdaptCachedBodyGlobalFilter(),
    ): GatewayFilter {
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
        val config =
            Binder(MapConfigurationPropertySource(definition.args))
                .bindOrCreate("", RetryGatewayFilterFactory.RetryConfig::class.java)
        assertThat(config.series).isEmpty()
        assertThat(config.statuses).isEmpty()
        assertThat(config.retries).isEqualTo(1)
        config.setRouteId("test")
        val factory = RetryGatewayFilterFactory()
        factory.setApplicationEventPublisher { event ->
            if (event is EnableBodyCachingEvent) cache.onApplicationEvent(event)
        }
        return factory.apply(config)
    }

    @Test
    fun retriesOnlyDnsAndConnectionFailuresOnceAcrossMethods() {
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
                    UnknownHostException("DNS not ready"),
                    ConnectException("Connection refused"),
                )) {
                val attempts = AtomicInteger()
                val exchange =
                    MockServerWebExchange.from(
                        MockServerHttpRequest.method(method, "/test").build(),
                    )
                createFilter()
                    .filter(
                        exchange,
                        GatewayFilterChain {
                            Mono.defer {
                                if (attempts.incrementAndGet() == 1) Mono.error(failure)
                                else Mono.empty()
                            }
                        },
                    )
                    .block(Duration.ofSeconds(5))
                assertThat(attempts.get()).isEqualTo(2)
            }
        }
    }

    @Test
    fun doesNotRetryTimeoutResetOrApplicationErrors() {
        for (failure in
            listOf(
                TimeoutException("response timeout"),
                IOException("connection reset after send"),
            )) {
            val attempts = AtomicInteger()
            assertThatThrownBy {
                    createFilter()
                        .filter(
                            MockServerWebExchange.from(MockServerHttpRequest.post("/save")),
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
            assertThat(attempts.get()).isEqualTo(1)
        }
        for (status in
            listOf(
                HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.BAD_GATEWAY,
                HttpStatus.SERVICE_UNAVAILABLE,
            )) {
            val attempts = AtomicInteger()
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/save"))
            createFilter()
                .filter(
                    exchange,
                    GatewayFilterChain {
                        Mono.defer {
                            attempts.incrementAndGet()
                            it.response.statusCode = status
                            Mono.empty()
                        }
                    },
                )
                .block(Duration.ofSeconds(5))
            assertThat(attempts.get()).isEqualTo(1)
        }
    }

    @Test
    fun stopsAfterOneRetryIfNoInstanceCanBeReached() {
        val attempts = AtomicInteger()
        assertThatThrownBy {
                createFilter()
                    .filter(
                        MockServerWebExchange.from(MockServerHttpRequest.get("/test")),
                        GatewayFilterChain {
                            Mono.defer {
                                attempts.incrementAndGet()
                                Mono.error(UnknownHostException())
                            }
                        },
                    )
                    .block(Duration.ofSeconds(5))
            }
            .isInstanceOf(Exception::class.java)
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun preservesPostBodyAcrossRetryUsingBuiltInBodyCaching() {
        val cache = AdaptCachedBodyGlobalFilter()
        val retry = createFilter(cache)
        val exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.post("/save").body("amount=100&memo=test"),
            )
        exchange.attributes[ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR] =
            Route.async().id("test").uri("http://application").predicate { true }.build()
        val bodies = mutableListOf<String>()
        val backend = GatewayFilterChain { request ->
            Mono.defer {
                assertThat(
                        request.getAttribute<Any>(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR),
                    )
                    .isNotNull()
                DataBufferUtils.join(request.request.body).flatMap { buffer ->
                    val content = ByteArray(buffer.readableByteCount())
                    buffer.read(content)
                    DataBufferUtils.release(buffer)
                    bodies.add(String(content, Charsets.UTF_8))
                    if (bodies.size == 1) Mono.error(ConnectException("before send"))
                    else Mono.empty<Void>()
                }
            }
        }
        RemoveCachedBodyFilter()
            .filter(
                exchange,
                GatewayFilterChain { outer ->
                    cache.filter(
                        outer,
                        GatewayFilterChain { cached -> retry.filter(cached, backend) },
                    )
                },
            )
            .block(Duration.ofSeconds(5))
        assertThat(bodies).containsExactly("amount=100&memo=test", "amount=100&memo=test")
    }
}
