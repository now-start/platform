package org.nowstart.admin.monitoring

import com.hazelcast.core.HazelcastInstance
import java.time.Instant
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.stereotype.Component

/** 로컬 멤버의 관측만 반환한다. 원격 호출이나 데이터 맵 조회는 수행하지 않는다. */
@Component
@Endpoint(id = "hazelcastcluster")
class HazelcastClusterEndpoint(private val hazelcast: HazelcastInstance) {
    @ReadOperation
    fun snapshot(): Map<String, Any> {
        val cluster = hazelcast.cluster
        return mapOf(
            "capturedAt" to Instant.now().toString(),
            "clusterName" to hazelcast.config.clusterName,
            "running" to hazelcast.lifecycleService.isRunning,
            "clusterState" to cluster.clusterState.name,
            "localMemberId" to cluster.localMember.uuid.toString(),
            "members" to
                cluster.members
                    .sortedBy { it.uuid.toString() }
                    .map {
                        mapOf(
                            "id" to it.uuid.toString(),
                            "address" to it.address.toString(),
                            "lite" to it.isLiteMember,
                        )
                    },
        )
    }
}
