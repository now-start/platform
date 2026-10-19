package org.nowstart.admin.config;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.eventstore.HazelcastEventStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static de.codecentric.boot.admin.server.config.AdminServerHazelcastAutoConfiguration.DEFAULT_NAME_EVENT_STORE_MAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class HazelcastClusterTest {
    @Test
    void sharesAdminEventsAndRetainsThemAfterMemberLoss() throws Exception {
        String cluster = "admin-test-" + UUID.randomUUID();
        HazelcastInstance first = Hazelcast.newHazelcastInstance(config(cluster, 15701));
        HazelcastInstance second = null;
        try {
            second = Hazelcast.newHazelcastInstance(config(cluster, 15702));
            HazelcastInstance survivor = second;
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(survivor.getCluster().getMembers()).hasSize(2));
            var firstStore = new HazelcastEventStore(first.getMap(DEFAULT_NAME_EVENT_STORE_MAP));
            var secondStore = new HazelcastEventStore(second.getMap(DEFAULT_NAME_EVENT_STORE_MAP));
            InstanceId id = InstanceId.of("test-service");
            var event = new InstanceRegisteredEvent(id, 0,
                    Registration.create("test-service", "http://localhost/health").build());
            firstStore.append(List.of(event)).block(Duration.ofSeconds(10));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(secondStore.find(id).collectList().block(Duration.ofSeconds(5)))
                            .containsExactly(event));
            // 강제 종료로 원본 멤버를 잃어도 동기 백업에서 조회되어야 한다.
            first.getLifecycleService().terminate();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(survivor.getCluster().getMembers()).hasSize(1);
                assertThat(secondStore.find(id).collectList().block(Duration.ofSeconds(5)))
                        .containsExactly(event);
            });
        } finally {
            if (first.getLifecycleService().isRunning()) first.shutdown();
            if (second != null) second.shutdown();
        }
    }

    private Config config(String cluster, int port) throws Exception {
        Config config = new HazelcastConfig().adminHazelcastConfig(cluster,
                List.of("127.0.0.1"));
        config.getNetworkConfig().getJoin().getTcpIpConfig()
                .setMembers(List.of("localhost:15701"));
        config.getNetworkConfig().setPort(port);
        config.setProperty("hazelcast.local.localAddress", "127.0.0.1");
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.io.input.thread.count", "1");
        config.setProperty("hazelcast.io.output.thread.count", "1");
        config.setProperty("hazelcast.partition.count", "17");
        config.setProperty("hazelcast.wait.seconds.before.join", "0");
        return config;
    }
}
