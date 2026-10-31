package ai.helper2.platform.quota;

import ai.helper2.platform.resilience.Resilience;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link QuotaCounter} in Redis hashes. The check and the count of the own and the global counter run in one Lua
 * script, so concurrent requests on any replica never pass a limit, and a key gets its one-day expiry with its first
 * count. Every call runs under the {@code redis} policy (ADR 0033) and throws when Redis cannot answer or the circuit
 * is open.
 */
public final class RedisQuotaCounter implements QuotaCounter {

    static final Duration KEEP = Duration.ofDays(1);

    /**
     * ARGV: own field, own limit, global field, global limit, expiry in ms; an empty field means no such counter.
     * KEYS: the own key (when there is one), then the global key (when there is one). Returns {verdict, own count}:
     * 0 taken, 1 own limit, 2 global limit.
     */
    private static final RedisScript<List> TAKE = RedisScript.of("""
        local i = 1
        local own, global
        if ARGV[1] ~= '' then own = KEYS[i]; i = i + 1 end
        if ARGV[3] ~= '' then global = KEYS[i] end
        local used = 0
        if own then
          used = tonumber(redis.call('HGET', own, ARGV[1]) or '0')
          if used >= tonumber(ARGV[2]) then return {1, used} end
        end
        if global then
          if tonumber(redis.call('HGET', global, ARGV[3]) or '0') >= tonumber(ARGV[4]) then return {2, used} end
          redis.call('HINCRBY', global, ARGV[3], 1)
          if redis.call('PTTL', global) < 0 then redis.call('PEXPIRE', global, ARGV[5]) end
        end
        if own then
          used = redis.call('HINCRBY', own, ARGV[1], 1)
          if redis.call('PTTL', own) < 0 then redis.call('PEXPIRE', own, ARGV[5]) end
        end
        return {0, used}
        """, List.class);

    /** KEYS: the key; ARGV: the field. Takes one off the field, never below 0. */
    private static final RedisScript<Long> GIVE_BACK = RedisScript.of("""
        if tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0') > 0 then
          return redis.call('HINCRBY', KEYS[1], ARGV[1], -1)
        end
        return 0
        """, Long.class);

    private static final Verdict[] VERDICTS = {Verdict.TAKEN, Verdict.LIMIT_REACHED, Verdict.GLOBAL_LIMIT_REACHED};

    private final StringRedisTemplate redis;
    private final Resilience resilience;

    public RedisQuotaCounter(StringRedisTemplate redis, Resilience resilience) {
        this.redis = redis;
        this.resilience = resilience;
    }

    @Override
    public Result take(Counter own, int ownLimit, Counter global, long globalLimit) {
        List<String> keys = new ArrayList<>(2);
        if (own != null) {
            keys.add(own.key());
        }
        if (global != null) {
            keys.add(global.key());
        }
        List<?> answer = resilience.call("redis", () -> redis.execute(TAKE, keys,
            own == null ? "" : own.field(), Integer.toString(ownLimit),
            global == null ? "" : global.field(), Long.toString(globalLimit),
            Long.toString(KEEP.toMillis())));
        return new Result(VERDICTS[((Number) answer.get(0)).intValue()], ((Number) answer.get(1)).longValue());
    }

    @Override
    public void giveBack(Counter counter) {
        resilience.call("redis", () -> redis.execute(GIVE_BACK, List.of(counter.key()), counter.field()));
    }

    @Override
    public long used(Counter counter) {
        Object value = resilience.call("redis", () -> redis.opsForHash().get(counter.key(), counter.field()));
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    @Override
    public void delete(Collection<String> keys) {
        resilience.run("redis", () -> redis.delete(keys));
    }
}
