package org.nowstart.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class RedisAuditIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Test
    void independentRepositoriesShareEventsAndRedisExpiresBodies() throws Exception {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        try {
            var redis = new StringRedisTemplate(factory);
            var first = new RedisAuditEventRepository(redis, JsonMapper.builder().build());
            var second = new RedisAuditEventRepository(redis, JsonMapper.builder().build());
            first.add(new AuditEvent("redis-user", "AUTHENTICATION_SUCCESS",
                    Map.of("details", Map.of("remoteAddress", "192.0.2.1", "sessionId", "raw-session"))));
            var events = second.find("redis-user", Instant.now().minusSeconds(10), "AUTHENTICATION_SUCCESS");
            assertThat(events).hasSize(1);
            var keys = redis.opsForZSet().range(RedisAuditEventRepository.INDEX, 0, -1);
            assertThat(keys).hasSize(1);
            String eventKey = keys.iterator().next();
            assertThat(redis.getExpire(eventKey)).isBetween(Duration.ofDays(30).toSeconds() - 10,
                    Duration.ofDays(30).toSeconds());
            redis.expire(eventKey, Duration.ofMillis(1));
            Thread.sleep(20);
            assertThat(redis.hasKey(eventKey)).isFalse();
            assertThat(second.find(null, null, null)).isEmpty();
            // 30일을 넘긴 색인 참조도 다음 조회 때 정리한다.
            redis.opsForZSet().add(RedisAuditEventRepository.INDEX, "old-reference",
                    Instant.now().minus(Duration.ofDays(31)).toEpochMilli());
            second.find(null, null, null);
            assertThat(redis.opsForZSet().score(RedisAuditEventRepository.INDEX, "old-reference")).isNull();
        } finally {
            factory.destroy();
        }
    }
}
