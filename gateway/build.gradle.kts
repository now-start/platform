version = "6.2.10"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-hazelcast")
    implementation("org.springframework.boot:spring-boot-session")
    implementation("com.hazelcast.spring:hazelcast-spring-session:4.0.0")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation(
        "org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j",
    )
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.security:spring-security-test")
}
