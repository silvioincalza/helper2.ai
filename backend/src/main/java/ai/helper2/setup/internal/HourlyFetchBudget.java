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

/**
 * Ceiling on the documents the setup guide fetches per UTC hour, across every replica. The GitHub token is the one
 * the Scout collects with: page views must not spend its hourly quota. Counted in Redis ({@code INCR} on a key per
 * hour, under the {@code redis} policy); when Redis cannot answer, each replica counts on its own from the last
 * value it saw, so an outage allows at most the budget per replica and never blocks reading by mistake.
 */
final class HourlyFetchBudget {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final int perHour;
    private final Clock clock;
    private final @Nullable StringRedisTemplate redis;
    private final Resilience resilience;
    private final Counter refused;
    private Instant hour = Instant.EPOCH;
    private int used;

    /** [redis] null counts in memory only. [refused] counts the fetches the budget turned down. */
    HourlyFetchBudget(int perHour, Clock clock, @Nullable StringRedisTemplate redis, Resilience resilience, Counter refused) {
        this.perHour = perHour;
        this.clock = clock;
        this.redis = redis;
        this.resilience = resilience;
        this.refused = refused;
    }

    static String key(Instant at) {
        return "h2:v1:setup:fetches:" + HOUR.format(at);
    }

    /** Takes one fetch from this hour's budget; false when it is used up (or set to 0). */
    boolean tryAcquire() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.HOURS);
        // Outside the lock: a slow Redis must not queue every caller behind it.
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

    /** This hour's count after taking one, or null when Redis cannot answer. The key outlives its hour by one. */
    private @Nullable Long sharedCount(String key) {
        if (redis == null) {
            return null;
        }
        try {
            return resilience.call("redis", () -> {
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
