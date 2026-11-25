version = "2.5.15"

dependencies {
    implementation("de.codecentric:spring-boot-admin-starter-client")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-server")
}

// Eureka Server 5.1.0 ships incomplete dashboard assets. The 5.0.4 templates are identical.
val dashboardAssets =
    configurations.detachedConfiguration(
        dependencies.create(
            "org.springframework.cloud:spring-cloud-netflix-eureka-server:5.0.4@jar",
        ),
    )

tasks.processResources {
    from(zipTree(dashboardAssets.elements.map { it.single().asFile })) {
        include("static/eureka/css/wro.css", "static/eureka/js/wro.js")
    }
}
