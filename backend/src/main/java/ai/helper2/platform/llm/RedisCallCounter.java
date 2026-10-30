package ai.helper2.platform.llm;

import ai.helper2.platform.resilience.Resilience;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis adapter for the daily budgets: one counter per budget name and UTC day, kept two days so the
 * key outlives its day in every time zone. INCR and PEXPIRE run in one script, so a key is never left without
 * an expiry (as RedisRateLimiter). The script runs under the {@code redis} policy (ADR 0033): a Redis error, or the open
 * circuit refusing the call, answers null and DailyBudget counts in memory, so an outage does not add a timeout to
 * every call.
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
    private final Resilience resilience;

    public RedisCallCounter(StringRedisTemplate redis, Resilience resilience) {
        this.redis = redis;
        this.resilience = resilience;
    }

    static String key(String name, LocalDate day) {
        return "h2:v1:budget:" + name + ":" + day;
    }

    @Override
    public Long increment(String name, LocalDate day) {
        try {
            return resilience.call("redis", () -> redis.execute(INCREMENT, List.of(key(name, day)), Long.toString(KEEP.toMillis())));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
