package org.nowstart.gateway

import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.client.discovery.EnableDiscoveryClient
import org.springframework.context.annotation.Bean

@EnableDiscoveryClient
@SpringBootApplication
class GatewayServiceApplication {
    @Bean fun createTraceRepository(): HttpExchangeRepository = InMemoryHttpExchangeRepository()
}

fun main(args: Array<String>) {
    runApplication<GatewayServiceApplication>(*args)
}
