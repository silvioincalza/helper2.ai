package ai.helper2.search.cache;

import ai.helper2.platform.CircuitBreaker;
import ai.helper2.search.CachedHits;
import ai.helper2.search.SearchCache;
import java.time.Clock;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis adapter for the search cache port.
 *
 * Failure behaviour: any error opens a {@link CircuitBreaker} for {@code openFor}; while open, calls fail
 * fast so a cache outage never adds per-source latency. The orchestrator turns failures into a
 * CACHE_UNAVAILABLE notice and queries sources live.
 */
public class RedisSearchCache implements SearchCache {

    private final StringRedisTemplate redis;
    private final SourceHitCodec codec;
    private final CircuitBreaker circuit;

    public RedisSearchCache(StringRedisTemplate redis, ObjectMapper json, Clock clock, Duration openFor) {
        this.redis = redis;
        this.codec = new SourceHitCodec(json);
        this.circuit = new CircuitBreaker("search cache", clock, openFor);
    }

    @Override
    public CachedHits get(String key) {
        guard();
        try {
            String raw = redis.opsForValue().get(key);
            return raw == null ? null : codec.decode(raw);
        } catch (RuntimeException e) {
            throw trip(e);
        }
    }

    @Override
    public void put(String key, CachedHits value, Duration ttl) {
        guard();
        try {
            redis.opsForValue().set(key, codec.encode(value), ttl);
        } catch (RuntimeException e) {
            throw trip(e);
        }
    }

    private void guard() {
        if (circuit.isOpen()) {
            throw new IllegalStateException("cache circuit open");
        }
    }

    private RuntimeException trip(RuntimeException e) {
        circuit.trip(e);
        return new IllegalStateException("cache unavailable", e);
    }
}
