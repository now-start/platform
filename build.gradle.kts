import org.springframework.boot.gradle.dsl.SpringBootExtension

plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.spring") version "2.4.21" apply false
    id("org.springframework.boot") version "4.2.0" apply false
    id("com.diffplug.spotless") version "8.12.0"
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
            mavenBom("de.codecentric:spring-boot-admin-dependencies:4.1.5")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2026.0.0")
            mavenBom("org.springdoc:springdoc-openapi-bom:3.2.1")
            mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.34.0")
            mavenBom("org.jolokia:jolokia-bom:2.6.5")
        }
        dependencies {
            // Netflix Eureka still requests older HttpClient and Guava releases.
            dependency("org.apache.httpcomponents:httpclient:4.5.14")
            dependency("com.google.guava:guava:33.8.0-jre")
        }
    }
    dependencies {
        add("implementation", "org.jetbrains.kotlin:kotlin-reflect")
        add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
        add("implementation", "io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
        add("developmentOnly", "org.springframework.boot:spring-boot-devtools")
        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
    }
    tasks.named("check") { dependsOn(rootProject.tasks.named("spotlessCheck")) }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        environment("SPRING_CONFIG_IMPORT", "")
    }
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
