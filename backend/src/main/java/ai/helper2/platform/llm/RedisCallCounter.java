package ai.helper2.platform.llm;

import ai.helper2.platform.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis adapter for the daily budgets: one counter per budget name and UTC day, kept two days so the
 * key outlives its day in every time zone. INCR and PEXPIRE run in one script, so a key is never left without
 * an expiry (as RedisRateLimiter). Any Redis error answers null and DailyBudget counts in memory;
 * after an error a {@link CircuitBreaker} leaves Redis alone for {@code openFor}, so an outage does not add a
 * timeout to every call.
 */
public final class RedisCallCounter implements CallCounter {

    private static final Duration KEEP = Duration.ofDays(2);

    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then
          redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        return count
        """, Long.class);

    private final StringRedisTemplate redis;
    private final CircuitBreaker circuit;

    public RedisCallCounter(StringRedisTemplate redis, Clock clock, Duration openFor) {
        this.redis = redis;
        this.circuit = new CircuitBreaker("budget counter", clock, openFor);
    }

    static String key(String name, LocalDate day) {
        return "h2:v1:budget:" + name + ":" + day;
    }

    @Override
    public Long increment(String name, LocalDate day) {
        if (circuit.isOpen()) {
            return null;
        }
        try {
            return redis.execute(INCREMENT, List.of(key(name, day)), Long.toString(KEEP.toMillis()));
        } catch (RuntimeException e) {
            circuit.trip(e);
            return null;
        }
    }
}
