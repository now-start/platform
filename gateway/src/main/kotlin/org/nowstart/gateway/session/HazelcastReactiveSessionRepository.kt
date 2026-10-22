package org.nowstart.gateway.session

import com.hazelcast.spring.session.HazelcastIndexedSessionRepository
import com.hazelcast.spring.session.HazelcastIndexedSessionRepository.HazelcastSession
import org.springframework.session.ReactiveSessionRepository
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/** Hazelcast의 변경분 저장과 세션 ID 교체를 WebFlux 이벤트 루프 밖에서 실행한다. */
class HazelcastReactiveSessionRepository(private val delegate: HazelcastIndexedSessionRepository) :
    ReactiveSessionRepository<HazelcastSession> {
    override fun createSession(): Mono<HazelcastSession> =
        Mono.fromCallable { delegate.createSession() }.subscribeOn(Schedulers.boundedElastic())

    override fun save(session: HazelcastSession): Mono<Void> =
        Mono.fromRunnable<Void> { delegate.save(session) }.subscribeOn(Schedulers.boundedElastic())

    override fun findById(id: String): Mono<HazelcastSession> =
        Mono.fromCallable { delegate.findById(id) }.subscribeOn(Schedulers.boundedElastic())

    override fun deleteById(id: String): Mono<Void> =
        Mono.fromRunnable<Void> { delegate.deleteById(id) }.subscribeOn(Schedulers.boundedElastic())
}
