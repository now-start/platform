package org.nowstart.admin

import de.codecentric.boot.admin.server.config.EnableAdminServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.client.discovery.EnableDiscoveryClient
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@EnableAdminServer
@EnableDiscoveryClient
@SpringBootApplication
class AdminServiceApplication

fun main(args: Array<String>) {
    runApplication<AdminServiceApplication>(*args)
}
