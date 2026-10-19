package org.nowstart.admin.config;

import de.codecentric.boot.admin.server.eventstore.HazelcastEventStore;
import de.codecentric.boot.admin.server.eventstore.InstanceEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false", "spring.application.name=admin-autoconfig-test",
        "admin.cluster.members=127.0.0.1", "spring.jmx.enabled=false",
        "management.server.port=0", "otel.sdk.disabled=true"
})
@DirtiesContext
class HazelcastAutoConfigurationTest {
    @Autowired InstanceEventStore eventStore;

    @Test
    void usesHazelcastInsteadOfLocalEventStore() {
        assertThat(eventStore).isInstanceOf(HazelcastEventStore.class);
    }
}
