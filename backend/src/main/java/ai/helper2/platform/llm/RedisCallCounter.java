package ai.helper2.platform.llm;

import ai.helper2.platform.resilience.Resilience;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis adapter for the daily budgets: one counter per budget name and UTC day, kept two days so
 * the key outlives its day in every time zone. The check against the limit, INCR and PEXPIRE run in
 * one script, so a refused call adds nothing and a key is never left without an expiry (as
 * RedisRateLimiter). The script runs under the {@code redis} policy (ADR 0033): a Redis error, or
 * the open circuit refusing the call, answers null and DailyBudget counts in memory, so an outage
 * does not add a timeout to every call.
 */
public final class RedisCallCounter implements CallCounter {

  private static final Duration KEEP = Duration.ofDays(2);

  /**
   * KEYS: the count. ARGV: expiry in ms, limit. At the limit, answers count + 1 and adds nothing.
   */
  private static final RedisScript<Long> INCREMENT =
      RedisScript.of(
          """
          local used = tonumber(redis.call('GET', KEYS[1]) or '0')
          if used >= tonumber(ARGV[2]) then return used + 1 end
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
          end
          return count
          """,
          Long.class);

  /**
   * KEYS: the count, the set of what was given back. ARGV: once, daily cap, expiry in ms. Returns
   * the index of the {@link GiveBack} outcome. The set holds the {@code once} ids, so its size is
   * the day's count given back.
   */
  private static final RedisScript<Long> GIVE_BACK =
      RedisScript.of(
          """
          if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 1 end
          if redis.call('SCARD', KEYS[2]) >= tonumber(ARGV[2]) then return 2 end
          if tonumber(redis.call('GET', KEYS[1]) or '0') <= 0 then return 3 end
          redis.call('DECR', KEYS[1])
          redis.call('SADD', KEYS[2], ARGV[1])
          if redis.call('PTTL', KEYS[2]) < 0 then redis.call('PEXPIRE', KEYS[2], ARGV[3]) end
          return 0
          """,
          Long.class);

  private static final GiveBack[] GIVE_BACKS = {
    GiveBack.GIVEN_BACK, GiveBack.ALREADY_GIVEN_BACK, GiveBack.CAP_REACHED, GiveBack.NOTHING_COUNTED
  };

  private final StringRedisTemplate redis;
  private final Resilience resilience;

  public RedisCallCounter(StringRedisTemplate redis, Resilience resilience) {
    this.redis = redis;
    this.resilience = resilience;
  }

  static String key(String name, LocalDate day) {
    return "h2:v1:budget:" + name + ":" + day;
  }

  /** The {@code once} ids given back on [day]; expires with the count. */
  static String givenBackKey(String name, LocalDate day) {
    return key(name, day) + ":given-back";
  }

  /**
   * Today's shared count, without adding one: 0 when Redis has no key yet (nothing used today),
   * null only when Redis cannot answer.
   */
  @Override
  public Long current(String name, LocalDate day) {
    try {
      String value = resilience.call("redis", () -> redis.opsForValue().get(key(name, day)));
      return value == null ? 0L : Long.valueOf(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public Long increment(String name, LocalDate day, int limit) {
    try {
      return resilience.call(
          "redis",
          () ->
              redis.execute(
                  INCREMENT,
                  List.of(key(name, day)),
                  Long.toString(KEEP.toMillis()),
                  Integer.toString(limit)));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Null when Redis cannot answer or the circuit is open, as for {@link #increment}. */
  @Override
  public GiveBack giveBack(String name, LocalDate day, String once, int dailyCap) {
    try {
      Long outcome =
          resilience.call(
              "redis",
              () ->
                  redis.execute(
                      GIVE_BACK,
                      List.of(key(name, day), givenBackKey(name, day)),
                      once,
                      Integer.toString(dailyCap),
                      Long.toString(KEEP.toMillis())));
      return outcome == null ? null : GIVE_BACKS[outcome.intValue()];
    } catch (RuntimeException e) {
      return null;
    }
  }
}
