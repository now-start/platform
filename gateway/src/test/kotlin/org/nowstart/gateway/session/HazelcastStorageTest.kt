package org.nowstart.gateway.session

import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.spring.session.HazelcastIndexedSessionRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.nowstart.gateway.audit.HazelcastAuditEventRepository
import org.nowstart.gateway.config.HazelcastConfiguration
import org.springframework.boot.actuate.audit.AuditEvent
import reactor.core.scheduler.Schedulers

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastStorageTest {
    private lateinit var member: HazelcastInstance
    private lateinit var delegate: HazelcastIndexedSessionRepository
    private lateinit var sessions: HazelcastReactiveSessionRepository

    @BeforeAll
    fun start() {
        val config =
            HazelcastConfiguration()
                .gatewayHazelcastConfig(
                    "storage-" + UUID.randomUUID(),
                    listOf("127.0.0.1"),
                    listOf("127.0.0.1"),
                    0,
                )
        member = Hazelcast.newHazelcastInstance(config)
        delegate =
            HazelcastIndexedSessionRepository(member)
                .setSessionMapName(HazelcastConfiguration.SESSION_MAP)
        delegate.afterPropertiesSet()
        sessions = HazelcastReactiveSessionRepository(delegate)
    }

    @AfterAll
    fun stop() {
        delegate.destroy()
        member.shutdown()
    }

    @Test
    fun sharesDeltaUpdatesRotatesIdsAndDeletesSessions() {
        val session = requireNotNull(sessions.createSession().block())
        assertThat(session.maxInactiveInterval).isEqualTo(Duration.ofMinutes(30))
        session.setAttribute("original", "value")
        sessions.save(session).block()
        val first = requireNotNull(sessions.findById(session.id).block())
        val second = requireNotNull(sessions.findById(session.id).block())
        first.setAttribute("first", "one")
        second.setAttribute("second", "two")
        sessions.save(first).block()
        sessions.save(second).block()
        val updated = requireNotNull(sessions.findById(session.id).block())
        assertThat(updated.getAttribute<String>("first")).isEqualTo("one")
        assertThat(updated.getAttribute<String>("second")).isEqualTo("two")
        val oldId = updated.id
        updated.changeSessionId()
        sessions.save(updated).block()
        assertThat(sessions.findById(oldId).block()).isNull()
        assertThat(sessions.findById(updated.id).block()).isNotNull()
        sessions.deleteById(updated.id).block()
        assertThat(sessions.findById(updated.id).block()).isNull()
    }

    @Test
    fun expiresInactiveSessionsAndRunsStorageOffEventLoop() {
        val thread =
            sessions
                .createSession()
                .map { Thread.currentThread().name }
                .subscribeOn(Schedulers.parallel())
                .block()
        assertThat(thread).startsWith("boundedElastic")
        val session = requireNotNull(sessions.createSession().block())
        session.maxInactiveInterval = Duration.ofSeconds(1)
        sessions.save(session).block()
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(sessions.findById(session.id).block()).isNull()
        }
    }

    @Test
    fun keepsThirtyDayRetentionFiltersAndApprovedAuditFields() {
        val events =
            member.getMap<String, HazelcastAuditEventRepository.StoredEvent>(
                HazelcastAuditEventRepository.MAP_NAME,
            )
        events.clear()
        val now = Instant.parse("2026-10-22T00:00:00Z")
        val audit = HazelcastAuditEventRepository(events, Clock.fixed(now, ZoneOffset.UTC))
        audit.add(
            AuditEvent(
                now.minus(Duration.ofDays(30)),
                "expired",
                "SUCCESS",
                emptyMap<String, Any>(),
            ),
        )
        assertThat(events.size).isZero()
        audit.add(
            AuditEvent(
                now,
                "user",
                "SUCCESS",
                mapOf(
                    "password" to "secret",
                    "details" to
                        mapOf(
                            "remoteAddress" to "203.0.113.8",
                            "sessionId" to "session",
                            "token" to "secret-token",
                        ),
                ),
            ),
        )
        audit.add(AuditEvent(now.minusSeconds(1), "user", "SUCCESS", emptyMap<String, Any>()))
        audit.add(AuditEvent(now, "other", "FAILURE", emptyMap<String, Any>()))
        val found = audit.find("user", now.minusSeconds(1), "SUCCESS")
        assertThat(found).hasSize(1)
        assertThat(found.first().data).containsOnlyKeys("details")
        assertThat(found.first().data["details"])
            .isEqualTo(mapOf("remoteAddress" to "203.0.113.8", "sessionId" to "session"))
        val entry = events.getEntryView(events.keys.first())
        assertThat(entry.expirationTime - System.currentTimeMillis())
            .isBetween(Duration.ofDays(30).toMillis() - 10_000, Duration.ofDays(30).toMillis())
    }

    @Test
    fun hazelcastExpiresAuditBodiesWithoutReads() {
        val events =
            member.getMap<String, HazelcastAuditEventRepository.StoredEvent>(
                HazelcastAuditEventRepository.MAP_NAME,
            )
        events.clear()
        val audit = HazelcastAuditEventRepository(member)
        audit.add(
            AuditEvent(
                Instant.now().minus(HazelcastAuditEventRepository.RETENTION).plusSeconds(2),
                "almost-expired",
                "TEST",
                emptyMap<String, Any>(),
            ),
        )
        assertThat(events.size).isEqualTo(1)
        val key = events.keys.first()
        await().atMost(Duration.ofSeconds(6)).untilAsserted {
            assertThat(events.containsKey(key)).isFalse()
        }
        assertThat(audit.find(null, null, null)).isEmpty()
    }
}
