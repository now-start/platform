import java.util.zip.ZipFile

version = "2.5.13"

val bootstrapVersion = "5.3.8"
val popperVersion = "2.11.8"
val dashboardAssetSources by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation("de.codecentric:spring-boot-admin-starter-client")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-server")
    dashboardAssetSources("org.webjars.npm:bootstrap:$bootstrapVersion")
    dashboardAssetSources("org.webjars.npm:popperjs__core:$popperVersion")
    dashboardAssetSources("org.springframework.cloud:spring-cloud-netflix-eureka-server")
}

// Eureka Server 5.1.0 ships the dashboard theme but omits Bootstrap from its CSS and JS.
val generateDashboardAssets by tasks.registering {
    val bootstrap = "META-INF/resources/webjars/bootstrap/$bootstrapVersion"
    val popper = "META-INF/resources/webjars/popperjs__core/$popperVersion"
    inputs
        .files(dashboardAssetSources)
        .withPropertyName("assetJars")
        .withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(layout.buildDirectory.dir("generated/eurekaDashboard"))

    doLast {
        fun readResource(path: String): String =
            inputs.files.files.firstNotNullOfOrNull { archive ->
                ZipFile(archive).use { zip ->
                    zip.getEntry(path)?.let { entry ->
                        zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    }
                }
            } ?: error("Missing dashboard resource: $path")

        val output = outputs.files.singleFile
        val assets = output.resolve("static/eureka")
        val css =
            readResource("$bootstrap/dist/css/bootstrap.min.css")
                .substringBefore("/*# sourceMappingURL=")
        val theme = readResource("static/eureka/css/wro.css")
        assets.resolve("css").mkdirs()
        assets.resolve("css/wro.css").writeText(css.trimEnd() + "\n" + theme)

        val js =
            readResource("$bootstrap/dist/js/bootstrap.bundle.min.js")
                .substringBefore("//# sourceMappingURL=")
        assets.resolve("js").mkdirs()
        assets.resolve("js/wro.js").writeText(js.trimEnd() + "\n")

        val licenses = output.resolve("META-INF/licenses")
        licenses.mkdirs()
        licenses.resolve("bootstrap.txt").writeText(readResource("$bootstrap/LICENSE"))
        licenses.resolve("popper.txt").writeText(readResource("$popper/LICENSE.md"))
    }
}

tasks.processResources { from(generateDashboardAssets) }
