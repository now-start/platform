package org.nowstart.gateway.audit

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.audit.AuditEvent
import org.springframework.boot.actuate.audit.AuditEventRepository
import org.springframework.stereotype.Repository

@Repository
class HazelcastAuditEventRepository(
    private val events: IMap<String, StoredEvent>,
    private val clock: Clock,
) : AuditEventRepository {
    @Autowired
    constructor(hazelcast: HazelcastInstance) : this(hazelcast.getMap(MAP_NAME), Clock.systemUTC())

    override fun add(event: AuditEvent) {
        val ttl = Duration.between(clock.instant(), event.timestamp.plus(RETENTION)).toMillis()
        if (ttl <= 0) return
        // 허용한 상세 값만 저장하고 자격 증명과 예외 메시지는 저장하지 않는다.
        val source = event.data["details"] as? Map<*, *>
        val details = linkedMapOf<String, String>()
        for (key in listOf("remoteAddress", "sessionId")) {
            (source?.get(key) as? String)?.let { details[key] = it }
        }
        events.set(
            UUID.randomUUID().toString(),
            StoredEvent(event.timestamp, event.principal, event.type, details),
            ttl,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun find(principal: String?, after: Instant?, type: String?): List<AuditEvent> {
        val cutoff = clock.instant().minus(RETENTION)
        return events.values
            .asSequence()
            .filter {
                it.timestamp.isAfter(cutoff) && (after == null || it.timestamp.isAfter(after))
            }
            .filter {
                (principal == null || it.principal == principal) &&
                    (type == null || it.type == type)
            }
            .sortedBy { it.timestamp }
            .map {
                AuditEvent(
                    it.timestamp,
                    it.principal,
                    it.type,
                    if (it.details.isEmpty()) emptyMap() else mapOf("details" to it.details),
                )
            }
            .toList()
    }

    data class StoredEvent(
        val timestamp: Instant,
        val principal: String,
        val type: String,
        val details: Map<String, String>,
    ) : Serializable

    companion object {
        const val MAP_NAME = "nowstart:gateway:audit"
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
