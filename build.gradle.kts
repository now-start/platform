import org.springframework.boot.gradle.dsl.SpringBootExtension
import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.spring") version "2.4.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("com.diffplug.spotless") version "8.10.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.gorylenko.gradle-git-properties") version "4.0.1" apply false
}

repositories { mavenCentral() }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.gorylenko.gradle-git-properties")

    group = "org.nowstart"
    configure<SpringBootExtension> { buildInfo() }
    configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }
    repositories { mavenCentral() }
    dependencyManagement {
        imports {
            mavenBom("de.codecentric:spring-boot-admin-dependencies:4.1.4")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.3")
            mavenBom("org.springdoc:springdoc-openapi-bom:3.1.1")
            mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.32.0")
        }
    }
    dependencies {
        add("implementation", "org.jetbrains.kotlin:kotlin-reflect")
        add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
        add("implementation", "io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
        add("developmentOnly", "org.springframework.boot:spring-boot-devtools")
        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
    }
    tasks.named<BootBuildImage>("bootBuildImage") {
        buildpacks =
            listOf(
                "urn:cnb:builder:paketo-buildpacks/java",
                "docker.io/paketobuildpacks/health-checker:latest",
            )
        environment.put("BP_HEALTH_CHECKER_ENABLED", "true")
    }
    tasks.named("check") { dependsOn(rootProject.tasks.named("spotlessCheck")) }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

spotless {
    kotlin {
        target("*/src/**/*.kt")
        ktfmt("0.65").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts")
        ktfmt("0.65").kotlinlangStyle()
    }
}
