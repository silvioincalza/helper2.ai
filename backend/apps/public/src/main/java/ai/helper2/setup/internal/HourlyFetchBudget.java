package ai.helper2.setup.internal;

import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.Counter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;

final class HourlyFetchBudget {

  private static final DateTimeFormatter HOUR =
      DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

  private final int perHour;
  private final Clock clock;
  private final @Nullable StringRedisTemplate redis;
  private final Resilience resilience;
  private final Counter refused;
  private Instant hour = Instant.EPOCH;
  private int used;

  HourlyFetchBudget(
      int perHour,
      Clock clock,
      @Nullable StringRedisTemplate redis,
      Resilience resilience,
      Counter refused) {
    this.perHour = perHour;
    this.clock = clock;
    this.redis = redis;
    this.resilience = resilience;
    this.refused = refused;
  }

  static String key(Instant at) {
    return "h2:v1:setup:fetches:" + HOUR.format(at);
  }

  boolean tryAcquire() {
    Instant now = clock.instant().truncatedTo(ChronoUnit.HOURS);
    Long shared = perHour <= 0 ? null : sharedCount(key(now));
    boolean ok;
    synchronized (this) {
      if (!now.equals(hour)) {
        hour = now;
        used = 0;
      }
      if (shared != null) {
        used = (int) Math.max(used, Math.min(shared, perHour));
        ok = shared <= perHour;
      } else {
        ok = used < perHour;
        if (ok) {
          used++;
        }
      }
    }
    if (!ok) {
      refused.increment();
    }
    return ok;
  }

  private @Nullable Long sharedCount(String key) {
    if (redis == null) {
      return null;
    }
    try {
      return resilience.call(
          "redis",
          () -> {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1) {
              redis.expire(key, Duration.ofHours(2));
            }
            return n;
          });
    } catch (RuntimeException e) {
      return null;
    }
  }
}
