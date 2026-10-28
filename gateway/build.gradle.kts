version = "6.2.4"

dependencies {
    add("implementation", "org.springframework.boot:spring-boot-starter-oauth2-client")
    add("implementation", "org.springframework.boot:spring-boot-starter-hazelcast")
    add("implementation", "org.springframework.boot:spring-boot-session")
    add("implementation", "com.hazelcast.spring:hazelcast-spring-session:4.0.0")
    add("implementation", "org.springframework.cloud:spring-cloud-starter-config")
    add("implementation", "org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    add("implementation", "org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    add(
        "implementation",
        "org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j",
    )
    add("testImplementation", "io.projectreactor:reactor-test")
    add("testImplementation", "org.springframework.security:spring-security-test")
}
