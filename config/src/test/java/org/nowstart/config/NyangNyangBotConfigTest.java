package org.nowstart.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class NyangNyangBotConfigTest {

    @Test
    void providesMariaDbInstantJdbcType() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("config/nyang-nyang-bot/nyang-nyang-bot.yaml"));

        assertThat(Objects.requireNonNull(yaml.getObject()))
                .containsEntry(
                        "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type",
                        "TIMESTAMP"
                );
    }
}
