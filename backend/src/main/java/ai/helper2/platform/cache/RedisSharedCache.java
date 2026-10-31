package ai.helper2.platform.cache;

import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The shared cache in Redis, with one TTL per cache. Every call runs under the {@code redis} policy (ADR 0033): a Redis
 * error, or the open circuit refusing the call, makes get miss and put do nothing. Meter
 * {@code helper2.shared.cache{cache, result=hit|miss}}; a miss includes Redis being unavailable.
 */
public class RedisSharedCache implements SharedCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSharedCache.class);

    private final String name;
    private final Duration ttl;
    private final StringRedisTemplate redis;
    private final Resilience resilience;
    private final MeterRegistry meters;

    public RedisSharedCache(String name, Duration ttl, StringRedisTemplate redis, Resilience resilience, MeterRegistry meters) {
        resilience.require("redis");
        this.name = name;
        this.ttl = ttl;
        this.redis = redis;
        this.resilience = resilience;
        this.meters = meters;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public @Nullable String get(String key) {
        String value;
        try {
            value = resilience.call("redis", () -> redis.opsForValue().get(key));
        } catch (RuntimeException e) {
            log.debug("{} cache unavailable: {}", name, e.getClass().getSimpleName());
            value = null;
        }
        meters.counter("helper2.shared.cache", "cache", name, "result", value == null ? "miss" : "hit").increment();
        return value;
    }

    @Override
    public void put(String key, String value) {
        try {
            resilience.run("redis", () -> redis.opsForValue().set(key, value, ttl));
        } catch (RuntimeException e) {
            log.debug("{} not cached, Redis unavailable: {}", name, e.getClass().getSimpleName());
        }
    }
}
