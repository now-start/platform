package org.nowstart.gateway.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RedisAuditEventRepository implements AuditEventRepository {
    static final Duration RETENTION = Duration.ofDays(30);
    // 같은 hash tag로 묶어 Redis Cluster에서도 Lua의 두 키가 같은 슬롯을 사용한다.
    static final String PREFIX = "nowstart:gateway:audit:{events}:";
    static final String INDEX = PREFIX + "index";
    private static final DefaultRedisScript<Long> ADD = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            redis.call('ZADD', KEYS[1], ARGV[3], KEYS[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final Clock clock;

    @Autowired
    public RedisAuditEventRepository(StringRedisTemplate redis, JsonMapper mapper) {
        this(redis, mapper, Clock.systemUTC());
    }

    RedisAuditEventRepository(StringRedisTemplate redis, JsonMapper mapper, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void add(AuditEvent event) {
        Instant now = clock.instant();
        long ttl = Duration.between(now, event.getTimestamp().plus(RETENTION)).toMillis();
        if (ttl <= 0) {
            return;
        }
        // 호출자가 자격 증명 등을 넘겨도 허용한 상세 값만 저장한다.
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        if (event.getData().get("details") instanceof Map<?, ?> source) {
            for (String key : List.of("remoteAddress", "sessionId")) {
                if (source.get(key) instanceof String value) {
                    details.put(key, value);
                }
            }
        }
        var stored = new StoredEvent(event.getTimestamp().toString(), event.getPrincipal(), event.getType(),
                details.isEmpty() ? Map.of() : Map.of("details", details));
        redis.execute(ADD, List.of(INDEX, PREFIX + UUID.randomUUID()), mapper.writeValueAsString(stored),
                Long.toString(ttl), Long.toString(event.getTimestamp().toEpochMilli()),
                Long.toString(now.minus(RETENTION).toEpochMilli()), Long.toString(RETENTION.toMillis()));
    }

    @Override
    public List<AuditEvent> find(String principal, Instant after, String type) {
        Instant cutoff = clock.instant().minus(RETENTION);
        redis.opsForZSet().removeRangeByScore(INDEX, Double.NEGATIVE_INFINITY, cutoff.toEpochMilli());
        double start = (after != null && after.isAfter(cutoff) ? after : cutoff).toEpochMilli();
        var keys = redis.opsForZSet().rangeByScore(INDEX, start, Double.POSITIVE_INFINITY);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        List<AuditEvent> events = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                // 조회와 본문 TTL 만료가 겹친 경우 제외한다.
                if (value == null) continue;
                StoredEvent stored = mapper.readValue(value, StoredEvent.class);
                Instant timestamp = Instant.parse(stored.timestamp());
                if (timestamp.isAfter(cutoff) && (after == null || timestamp.isAfter(after))
                        && (principal == null || principal.equals(stored.principal()))
                        && (type == null || type.equals(stored.type()))) {
                    events.add(new AuditEvent(timestamp, stored.principal(), stored.type(), stored.data()));
                }
            }
        }
        return events;
    }

    public record StoredEvent(String timestamp, String principal, String type, Map<String, Object> data) {}
}
