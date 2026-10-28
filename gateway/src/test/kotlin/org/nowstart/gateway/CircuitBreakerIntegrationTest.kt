package org.nowstart.gateway

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cloud.gateway.filter.FilterDefinition
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.cloud.gateway.route.RouteDefinitionLocator
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CircuitBreakerIntegrationTest {
    private lateinit var backend: DisposableServer
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var client: WebTestClient
    private lateinit var registry: CircuitBreakerRegistry
    private val attempts = AtomicInteger()

    @BeforeAll
    fun start() {
        backend =
            HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle { request, response ->
                    val attempt = attempts.incrementAndGet()
                    val path = request.uri()
                    if (path == "/disconnect") {
                        response.withConnection { it.dispose() }
                        return@handle Mono.empty<Void>()
                    }
                    val status =
                        when {
                            path == "/recover" -> if (attempt % 2 == 1) 503 else 200
                            path.startsWith("/status/") -> path.substringAfterLast('/').toInt()
                            else -> 200
                        }
                    val result =
                        response
                            .status(status)
                            .header("X-Backend", "preserved")
                            .sendString(Mono.just("backend-$status"))
                            .then()
                    if (path == "/slow") result.delaySubscription(Duration.ofSeconds(2)) else result
                }
                .bindNow()
        val environment = StandardEnvironment()
        // 실제 중앙 설정의 Gateway/서킷 설정만 사용한다. 인증·암호화 값은 로드하지 않는다.
        YamlPropertySourceLoader()
            .load(
                "gateway-config",
                FileSystemResource("../config/src/main/resources/config/gateway/gateway.yaml"),
            )
            .forEach { source ->
                val values =
                    (source as org.springframework.core.env.EnumerablePropertySource<*>)
                        .propertyNames
                        .filter {
                            it.startsWith("spring.cloud.gateway.") ||
                                it.startsWith("spring.cloud.circuitbreaker.")
                        }
                        .associateWith { source.getProperty(it)!! }
                environment.propertySources.addLast(
                    MapPropertySource("filtered-${source.name}", values),
                )
            }
        context =
            SpringApplicationBuilder(TestApp::class.java)
                .environment(environment)
                .web(WebApplicationType.REACTIVE)
                .run(
                    "--spring.config.name=circuit-test",
                    "--spring.cloud.config.enabled=false",
                    "--spring.cloud.discovery.enabled=false",
                    "--spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
                    "--eureka.client.enabled=false",
                    "--otel.sdk.disabled=true",
                    "--server.port=0",
                    "--test.backend=http://127.0.0.1:${backend.port()}",
                )
        registry = context.getBean(CircuitBreakerRegistry::class.java)
        client =
            WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:${context.environment.getProperty("local.server.port")}")
                .responseTimeout(Duration.ofSeconds(10))
                .build()
    }

    @BeforeEach
    fun reset() {
        attempts.set(0)
        registry.allCircuitBreakers.forEach { it.reset() }
    }

    @AfterAll
    fun stop() {
        if (::context.isInitialized) context.close()
        if (::backend.isInitialized) backend.disposeNow()
    }

    @Test
    fun retrySuccessCountsAsOneSuccess() {
        client
            .get()
            .uri("/barosteel/recover")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .isEqualTo("backend-200")
        assertThat(attempts.get()).isEqualTo(2)
        assertThat(breaker().metrics.numberOfSuccessfulCalls).isEqualTo(1)
        assertThat(breaker().metrics.numberOfFailedCalls).isZero()
    }

    @Test
    fun httpErrorsAreRetriedButDoNotCountAsCircuitFailures() {
        for (status in listOf(500, 502, 503, 504)) {
            reset()
            client
                .get()
                .uri("/barosteel/status/$status")
                .exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectHeader()
                .valueEquals("X-Backend", "preserved")
                .expectBody(String::class.java)
                .isEqualTo("backend-$status")
            assertThat(attempts.get()).isEqualTo(4)
            assertThat(breaker().metrics.numberOfFailedCalls).isZero()
        }
    }

    @Test
    fun postIsNotRetriedAndClientErrorsDoNotCountAsFailures() {
        client
            .post()
            .uri("/barosteel/status/503")
            .exchange()
            .expectStatus()
            .isEqualTo(503)
            .expectBody(String::class.java)
            .isEqualTo("backend-503")
        assertThat(attempts.get()).isEqualTo(1)
        assertThat(breaker().metrics.numberOfFailedCalls).isZero()
        client.get().uri("/barosteel/status/404").exchange().expectStatus().isNotFound()
        assertThat(attempts.get()).isEqualTo(2)
        assertThat(breaker().metrics.numberOfFailedCalls).isZero()
        assertThat(breaker().metrics.numberOfSuccessfulCalls).isEqualTo(2)
    }

    @Test
    fun openRejectsWithoutBackendCallsAndHalfOpenCanRecover() {
        breaker().transitionToOpenState()
        client.get().uri("/barosteel/ok").exchange().expectStatus().isEqualTo(503)
        assertThat(attempts.get()).isZero()
        client
            .get()
            .uri("/lotto/ok")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .isEqualTo("backend-200")
        assertThat(registry.circuitBreaker("lotto").state).isEqualTo(CircuitBreaker.State.CLOSED)
        // OPEN 대기 60초를 실제로 기다리지 않고 회복 시험 단계로 이동한다.
        breaker().transitionToHalfOpenState()
        repeat(10) { client.get().uri("/barosteel/ok").exchange().expectStatus().isOk() }
        assertThat(breaker().state).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun connectionErrorsAreRecordedAndNotSwallowed() {
        client.get().uri("/unavailable/ok").exchange().expectStatus().is5xxServerError()
        assertThat(registry.circuitBreaker("unavailable").metrics.numberOfFailedCalls).isEqualTo(1)
    }

    @Test
    fun timeLimiterIsDisabledAndSlowResponsesSucceed() {
        client
            .get()
            .uri("/barosteel/slow")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .isEqualTo("backend-200")
        assertThat(breaker().metrics.numberOfFailedCalls).isZero()
        assertThat(context.getBean(TimeLimiterRegistry::class.java).allTimeLimiters).isEmpty()
        assertThat(
                context
                    .getBean(MeterRegistry::class.java)
                    .find("resilience4j.circuitbreaker.calls")
                    .tag("name", "barosteel")
                    .timers(),
            )
            .isNotEmpty()
    }

    private fun breaker(): CircuitBreaker = registry.circuitBreaker("barosteel")

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class TestApp {
        @Bean
        fun routes(environment: org.springframework.core.env.Environment): RouteDefinitionLocator =
            RouteDefinitionLocator {
                Flux.fromIterable(
                    listOf("barosteel", "lotto", "unavailable").map { name ->
                        RouteDefinition().apply {
                            setId(name)
                            setUri(
                                java.net.URI(
                                    environment.getRequiredProperty("test.backend"),
                                ),
                            )
                            setPredicates(listOf(PredicateDefinition("Path=/$name/**")))
                            setFilters(
                                listOf(
                                    FilterDefinition(
                                        if (name == "unavailable") "SetPath=/disconnect"
                                        else "StripPrefix=1",
                                    ),
                                ),
                            )
                        }
                    },
                )
            }

        @Bean
        fun security(http: ServerHttpSecurity): SecurityWebFilterChain =
            http.csrf { it.disable() }.authorizeExchange { it.anyExchange().permitAll() }.build()
    }
}
