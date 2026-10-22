package org.nowstart.admin.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl
import org.springdoc.core.properties.SwaggerUiConfigProperties
import org.springframework.cloud.client.discovery.DiscoveryClient
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled

@Configuration
class SwaggerConfig(
    private val discoveryClient: DiscoveryClient,
    private val properties: SwaggerUiConfigProperties,
) {
    @PostConstruct
    fun init() {
        properties.urls = getSwaggerUrls()
        properties.configUrl = "/admin/v3/api-docs/swagger-config"
        properties.docExpansion = "none"
        properties.operationsSorter = "alpha"
        properties.tagsSorter = "alpha"
        properties.filter = "true"
        properties.setDisplayRequestDuration(true)
        properties.setDeepLinking(true)
        properties.setTryItOutEnabled(true)
        properties.setDisplayOperationId(true)
        properties.setShowExtensions(true)
    }

    @Scheduled(fixedDelay = 30000)
    fun refreshSwaggerUrls() {
        log.debug("Refreshing Swagger URLs from DiscoveryClient")
        properties.urls = getSwaggerUrls()
    }

    private fun getSwaggerUrls(): Set<SwaggerUrl> =
        discoveryClient.services
            .filter { it.lowercase() !in EXCLUDE_SERVICES }
            .map { serviceId ->
                val url = "$GATEWAY_BASE_URL/$serviceId/v3/api-docs"
                log.info("Registering Swagger URL via Gateway: {}", url)
                SwaggerUrl().apply {
                    name = serviceId
                    setUrl(url)
                }
            }
            .toSet()

    companion object {
        private val log = LoggerFactory.getLogger(SwaggerConfig::class.java)
        private const val GATEWAY_BASE_URL = "https://spring.nowstart.org"
        private val EXCLUDE_SERVICES = setOf("gateway", "eureka", "admin", "config")
    }
}
