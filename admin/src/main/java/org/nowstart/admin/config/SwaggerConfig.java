package org.nowstart.admin.config;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SwaggerConfig {

    private static final String GATEWAY_BASE_URL = "https://spring.nowstart.org";
    private static final Set<String> EXCLUDE_SERVICES = Set.of("gateway", "eureka", "admin", "config");

    private final DiscoveryClient discoveryClient;
    private final SwaggerUiConfigProperties properties;

    @PostConstruct
    public void init() {
        // 초기 URL 등록
        properties.setUrls(getSwaggerUrls());

        // UI 옵션
        properties.setConfigUrl("/admin/v3/api-docs/swagger-config");
        properties.setDocExpansion("none");
        properties.setOperationsSorter("alpha");
        properties.setTagsSorter("alpha");
        properties.setFilter("true");
        properties.setDisplayRequestDuration(true);
        properties.setDeepLinking(true);
        properties.setTryItOutEnabled(true);
        properties.setDisplayOperationId(true);
        properties.setShowExtensions(true);
    }

    @Scheduled(fixedDelay = 30000)
    public void refreshSwaggerUrls() {
        log.debug("Refreshing Swagger URLs from DiscoveryClient");
        properties.setUrls(getSwaggerUrls());
    }

    private Set<AbstractSwaggerUiConfigProperties.SwaggerUrl> getSwaggerUrls() {
        return discoveryClient.getServices().stream()
                .filter(serviceId -> !EXCLUDE_SERVICES.contains(serviceId.toLowerCase()))
                .map(serviceId -> {
                    String url = String.format("%s/%s/v3/api-docs", GATEWAY_BASE_URL, serviceId);
                    log.info("Registering Swagger URL via Gateway: {}", url);

                    AbstractSwaggerUiConfigProperties.SwaggerUrl swaggerUrl = new AbstractSwaggerUiConfigProperties.SwaggerUrl();
                    swaggerUrl.setName(serviceId);
                    swaggerUrl.setUrl(url);
                    return swaggerUrl;
                })
                .collect(Collectors.toSet());
    }
}
