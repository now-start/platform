package org.nowstart.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class RedisAuditEventRepositoryTest {
    private final Instant now = Instant.parse("2026-10-19T00:00:00Z");
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RedisAuditEventRepository repository = new RedisAuditEventRepository(redis, mapper,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void usesThirtyDayTtlAndStoresOnlyAllowedDetails() {
        repository.add(new AuditEvent(now, "user", "SUCCESS", Map.of(
                "password", "secret", "details", Map.of("remoteAddress", "192.0.2.1",
                        "sessionId", "original-session", "token", "secret-token"))));
        var invocation = mockingDetails(redis).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("execute")).findFirst().orElseThrow();
        Object[] arguments = invocation.getArguments();
        assertThat(arguments[2].toString()).contains("original-session", "192.0.2.1")
                .doesNotContain("password", "secret", "token");
        assertThat(arguments[3]).isEqualTo(Long.toString(Duration.ofDays(30).toMillis()));
        assertThat(arguments[6]).isEqualTo(Long.toString(Duration.ofDays(30).toMillis()));
    }

    @Test
    void alreadyExpiredEventsAreNotStored() {
        repository.add(new AuditEvent(now.minus(Duration.ofDays(30)), "user", "TEST", Map.of()));
        verifyNoInteractions(redis);
    }

    @Test
    void filtersPrincipalTypeAndStrictTimestampAndSkipsExpiredBodies() {
        var keys = new LinkedHashSet<>(List.of("one", "two", "three", "expired"));
        when(redis.opsForZSet().rangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(keys);
        when(redis.opsForValue().multiGet(keys)).thenReturn(Arrays.asList(
                json(now.minusSeconds(1), "user", "SUCCESS"),
                json(now, "user", "SUCCESS"),
                json(now, "other", "FAILURE"), null));
        var found = repository.find("user", now.minusSeconds(1), "SUCCESS");
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getTimestamp()).isEqualTo(now);
        assertThat(found.getFirst().getData().get("details"))
                .isEqualTo(Map.of("remoteAddress", "192.0.2.1", "sessionId", "original-session"));
        verify(redis.opsForZSet()).removeRangeByScore(RedisAuditEventRepository.INDEX,
                Double.NEGATIVE_INFINITY, now.minus(Duration.ofDays(30)).toEpochMilli());
    }

    private String json(Instant timestamp, String principal, String type) {
        return mapper.writeValueAsString(new RedisAuditEventRepository.StoredEvent(timestamp.toString(), principal, type,
                Map.of("details", Map.of("remoteAddress", "192.0.2.1", "sessionId", "original-session"))));
    }
}
