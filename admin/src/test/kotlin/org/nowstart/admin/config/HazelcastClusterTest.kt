package org.nowstart.admin.config

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import de.codecentric.boot.admin.server.config.AdminServerHazelcastAutoConfiguration.DEFAULT_NAME_EVENT_STORE_MAP
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent
import de.codecentric.boot.admin.server.domain.values.InstanceId
import de.codecentric.boot.admin.server.domain.values.Registration
import de.codecentric.boot.admin.server.eventstore.HazelcastEventStore
import java.time.Duration
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test

class HazelcastClusterTest {
    @Test
    fun sharesAdminEventsAndRetainsThemAfterMemberLoss() {
        val cluster: String = "admin-test-" + UUID.randomUUID()
        val first: HazelcastInstance = Hazelcast.newHazelcastInstance(config(cluster, emptyList()))
        var second: HazelcastInstance? = null
        try {
            second =
                Hazelcast.newHazelcastInstance(
                    config(cluster, listOf("127.0.0.1:${first.cluster.localMember.address.port}")),
                )
            val survivor: HazelcastInstance = second
            await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted({ assertThat(survivor.getCluster().getMembers()).hasSize(2) })
            val firstStore = HazelcastEventStore(first.getMap(DEFAULT_NAME_EVENT_STORE_MAP))
            val secondStore = HazelcastEventStore(second.getMap(DEFAULT_NAME_EVENT_STORE_MAP))
            val id: InstanceId = InstanceId.of("test-service")
            val event =
                InstanceRegisteredEvent(
                    id,
                    0,
                    Registration.create("test-service", "http://localhost/health").build(),
                )
            firstStore.append(listOf(event)).block(Duration.ofSeconds(10))
            await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                    {
                        assertThat(secondStore.find(id).collectList().block(Duration.ofSeconds(5)))
                            .containsExactly(event)
                    },
                )
            // 강제 종료로 원본 멤버를 잃어도 동기 백업에서 조회되어야 한다.
            first.getLifecycleService().terminate()
            await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                    {
                        assertThat(survivor.getCluster().getMembers()).hasSize(1)
                        assertThat(secondStore.find(id).collectList().block(Duration.ofSeconds(5)))
                            .containsExactly(event)
                    },
                )
        } finally {
            if (first.getLifecycleService().isRunning()) first.shutdown()
            if (second != null) second.shutdown()
        }
    }

    private fun config(cluster: String, members: List<String>): Config {
        val config: Config =
            HazelcastConfiguration()
                .hazelcastConfig(
                    cluster,
                    members,
                    "127.0.0.0/8",
                    0,
                )
        config.setProperty("hazelcast.operation.thread.count", "2")
        config.setProperty("hazelcast.operation.generic.thread.count", "2")
        config.setProperty("hazelcast.io.input.thread.count", "1")
        config.setProperty("hazelcast.io.output.thread.count", "1")
        config.setProperty("hazelcast.partition.count", "17")
        config.setProperty("hazelcast.wait.seconds.before.join", "0")
        return config
    }
}
