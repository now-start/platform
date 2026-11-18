package org.nowstart.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

class NyangNyangBotConfigTest {

    @Test
    fun providesPostgresqlSessionTimeZone() {
        val yaml: YamlPropertiesFactoryBean = YamlPropertiesFactoryBean()
        yaml.setResources(ClassPathResource("config/nyang-nyang-bot/nyang-nyang-bot.yaml"))

        assertThat(requireNotNull(yaml.getObject()))
            .containsEntry(
                "spring.datasource.hikari.connection-init-sql",
                "SET TIME ZONE 'Asia/Seoul'",
            )
    }

    @Test
    fun providesEncryptedGoogleServiceAccountJson() {
        val yaml: YamlPropertiesFactoryBean = YamlPropertiesFactoryBean()
        yaml.setResources(ClassPathResource("config/nyang-nyang-bot/nyang-nyang-bot.yaml"))

        val key = requireNotNull(yaml.getObject()).getProperty("google.spreadsheet.key")
        assertThat(key).startsWith("{cipher}")
        assertThat(key.removePrefix("{cipher}")).matches("[0-9a-fA-F]+")
    }
}
