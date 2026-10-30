package ai.helper2.platform;

import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Fixed-window rate limit counted in Redis, so every replica sees the same count per client. One key per limiter
 * and client; the client's first request creates it with the window as its expiry, so the window ends on Redis'
 * clock and what is left of it is the Retry-After. INCR and PEXPIRE run in one script: a key is never left
 * without an expiry (which would block that client for good).
 *
 * <p>Fails open: the script runs under the {@code redis} policy (ADR 0033); on a Redis error, or while the circuit is
 * open, each replica counts in its own memory with the same limit, and every such decision is counted in
 * {@code helper2.rate-limit.fallback}.
 */
public final class RedisRateLimiter implements RateLimiter {

    static final String KEY_PREFIX = "h2:v1:rate:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> COUNT = RedisScript.of("""
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then
          redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        return {count, redis.call('PTTL', KEYS[1])}
        """, List.class);

    private final String name;
    private final int limit;
    private final Duration window;
    private final StringRedisTemplate redis;
    private final Resilience resilience;
    private final RateLimiter fallback;
    private final Counter fallbacks;

    /** {@code name} is the key's namespace and the meter's {@code limiter} tag: one per limit, not per endpoint. */
    public RedisRateLimiter(String name, int limit, Duration window, StringRedisTemplate redis, Resilience resilience,
                            RateLimiter fallback, MeterRegistry meters) {
        this.name = name;
        this.limit = limit;
        this.window = window;
        this.redis = redis;
        this.resilience = resilience;
        this.fallback = fallback;
        this.fallbacks = Counter.builder("helper2.rate-limit.fallback")
            .description("Rate-limit decisions made in one replica's memory because Redis was unavailable")
            .tag("limiter", name).register(meters);
    }

    static String key(String name, String clientAddress) {
        return KEY_PREFIX + name + ":" + ClientKey.of(clientAddress);
    }

    @Override
    public long acquire(String clientAddress) {
        try {
            return resilience.call("redis", () -> fromRedis(clientAddress));
        } catch (RuntimeException e) {
            fallbacks.increment();
            return fallback.acquire(clientAddress);
        }
    }

    private long fromRedis(String clientAddress) {
        List<?> result = redis.execute(COUNT, List.of(key(name, clientAddress)), Long.toString(window.toMillis()));
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("unexpected rate-limit script result: " + result);
        }
        long count = ((Number) result.get(0)).longValue();
        long millisLeft = ((Number) result.get(1)).longValue();
        if (count <= limit) {
            return 0;
        }
        // Rounded up, so a client that waits as told is never refused again for the same window.
        return Math.max(1, (millisLeft + 999) / 1000);
    }
}
