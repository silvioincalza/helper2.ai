package ai.helper2.platform.quota;

import ai.helper2.platform.resilience.Resilience;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public final class RedisQuotaCounter implements QuotaCounter {
  public static final Duration KEEP = Duration.ofDays(1);

  private static final RedisScript<List> TAKE =
      RedisScript.of(
          """
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
          """,
          List.class);

  private static final RedisScript<Long> ADD =
      RedisScript.of(
          """
          local used = redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])
          if redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
          return used
          """,
          Long.class);

  private static final RedisScript<Long> GIVE_BACK =
      RedisScript.of(
          """
          if tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0') > 0 then
            return redis.call('HINCRBY', KEYS[1], ARGV[1], -1)
          end
          return 0
          """,
          Long.class);

  private static final Verdict[] VERDICTS = {
    Verdict.TAKEN, Verdict.LIMIT_REACHED, Verdict.GLOBAL_LIMIT_REACHED
  };

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
    List<?> answer =
        resilience.call(
            "redis",
            () ->
                redis.execute(
                    TAKE,
                    keys,
                    own == null ? "" : own.field(),
                    Integer.toString(ownLimit),
                    global == null ? "" : global.field(),
                    Long.toString(globalLimit),
                    Long.toString(KEEP.toMillis())));
    return new Result(
        VERDICTS[((Number) answer.get(0)).intValue()], ((Number) answer.get(1)).longValue());
  }

  @Override
  public void add(Counter counter, long amount) {
    resilience.call(
        "redis",
        () ->
            redis.execute(
                ADD,
                List.of(counter.key()),
                counter.field(),
                Long.toString(amount),
                Long.toString(KEEP.toMillis())));
  }

  @Override
  public void giveBack(Counter counter) {
    resilience.call(
        "redis", () -> redis.execute(GIVE_BACK, List.of(counter.key()), counter.field()));
  }

  @Override
  public long used(Counter counter) {
    Object value =
        resilience.call("redis", () -> redis.opsForHash().get(counter.key(), counter.field()));
    return value == null ? 0 : Long.parseLong(value.toString());
  }
}
