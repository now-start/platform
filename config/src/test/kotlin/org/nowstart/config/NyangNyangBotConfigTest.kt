package org.nowstart.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

class NyangNyangBotConfigTest {

    @Test
    fun providesMariaDbInstantJdbcType() {
        val yaml: YamlPropertiesFactoryBean = YamlPropertiesFactoryBean()
        yaml.setResources(ClassPathResource("config/nyang-nyang-bot/nyang-nyang-bot.yaml"))

        assertThat(requireNotNull(yaml.getObject()))
            .containsEntry(
                "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type",
                "TIMESTAMP",
            )
    }

    @Test
    fun readsGoogleServiceAccountKeyFromDockerSecret() {
        val yaml: YamlPropertiesFactoryBean = YamlPropertiesFactoryBean()
        yaml.setResources(ClassPathResource("config/nyang-nyang-bot/nyang-nyang-bot.yaml"))

        assertThat(requireNotNull(yaml.getObject()))
            .containsEntry("google.spreadsheet.key", "/run/secrets/google-sheet-key.json")
    }
}
