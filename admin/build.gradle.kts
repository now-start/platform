version = "2.11.19"

// Keep rolling deployments compatible with the existing Admin cluster.
dependencyManagement { dependencies { dependency("com.hazelcast:hazelcast:5.5.0") } }

dependencies {
    add("implementation", "org.springframework.boot:spring-boot-starter-hazelcast")
    add("implementation", "org.springframework.boot:spring-boot-starter-web")
    add("implementation", "org.springframework.cloud:spring-cloud-starter-config")
    add("implementation", "org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    add("implementation", "de.codecentric:spring-boot-admin-starter-server")
    add("implementation", "org.springdoc:springdoc-openapi-starter-webmvc-ui")
}
