package com.ticketing.finalphase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Advisory cache only. PostgreSQL always enforces stock correctness. */
@Component
public class RedisGate implements AdmissionGate {
    private static final Logger log = LoggerFactory.getLogger(RedisGate.class);
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
        if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end
        local stock = redis.call('GET', KEYS[1])
        if not stock then return -1 end
        if tonumber(stock) <= 0 then return 0 end
        redis.call('DECR', KEYS[1])
        redis.call('SADD', KEYS[2], ARGV[1])
        redis.call('EXPIRE', KEYS[2], 120)
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
          if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end
          return 1
        end
        return 0
        """, Long.class);
    public RedisGate(StringRedisTemplate redis, @Value("${ticketing.redis-enabled:true}") boolean enabled) {
        this.redis = redis; this.enabled = enabled;
    }
    private List<String> keys(long event) { return List.of("ticket:{" + event + "}:stock", "ticket:{" + event + "}:tokens"); }
    private String token(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public long acquire(long event, String key) {
        if (!enabled) return -1;
        try { Long result = redis.execute(ACQUIRE, keys(event), token(key)); return result == null ? -1 : result; }
        catch (RuntimeException e) { log.debug("Redis unavailable; DB remains authoritative", e); return -1; }
    }
    public void release(long event, String key) {
        if (!enabled) return;
        try { redis.execute(RELEASE, keys(event), token(key)); }
        catch (RuntimeException e) { log.debug("Cache release deferred to reconciliation", e); }
    }
    public void project(long event, int remaining) {
        if (!enabled) return;
        // A projection can race with admission; it is not a distributed transaction.
        // A stale high cache cannot oversell because the DB conditional UPDATE remains mandatory.
        redis.opsForValue().set(keys(event).getFirst(), Integer.toString(remaining), java.time.Duration.ofSeconds(15));
    }
}
