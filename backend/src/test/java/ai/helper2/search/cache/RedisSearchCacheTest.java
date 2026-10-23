package ai.helper2.search.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.search.CachedHits;
import ai.helper2.testing.MovableClock;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/** The search cache is optional: a Redis error fails fast for a while instead of slowing every search. */
class RedisSearchCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    /** In-memory Redis for get and set-with-ttl; down = every call fails. */
    private static final class FakeRedis extends StringRedisTemplate {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Duration> ttls = new HashMap<>();
        boolean down;
        int calls;

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            calls++;
            if (down) {
                throw new RedisConnectionFailureException("down");
            }
            return (ValueOperations<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ValueOperations.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> values.get((String) args[0]);
                    case "set" -> {
                        values.put((String) args[0], (String) args[1]);
                        ttls.put((String) args[0], (Duration) args[2]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
    }

    private final FakeRedis redis = new FakeRedis();
    private final MovableClock clock = new MovableClock(NOW);
    private final RedisSearchCache cache = new RedisSearchCache(redis, JsonMapper.builder().build(), clock, Duration.ofSeconds(30));

    @Test
    void storesWithTheTtlAndReadsBack() {
        cache.put("k", new CachedHits(List.of(), NOW), Duration.ofMinutes(30));

        assertThat(redis.ttls).containsEntry("k", Duration.ofMinutes(30));
        assertThat(cache.get("k")).isEqualTo(new CachedHits(List.of(), NOW));
        assertThat(cache.get("missing")).isNull();
    }

    @Test
    void anUnreadableEntryIsAMiss() {
        redis.values.put("k", "not json");
        assertThat(cache.get("k")).isNull();
    }

    @Test
    void afterAFailureRedisIsLeftAloneUntilTheCircuitCloses() {
        redis.down = true;
        assertThatThrownBy(() -> cache.get("k")).isInstanceOf(IllegalStateException.class).hasMessage("cache unavailable");
        assertThatThrownBy(() -> cache.put("k", new CachedHits(List.of(), NOW), Duration.ofMinutes(1)))
            .isInstanceOf(IllegalStateException.class).hasMessage("cache circuit open");
        assertThat(redis.calls).isEqualTo(1);

        redis.down = false;
        clock.now = NOW.plusSeconds(31);
        assertThat(cache.get("k")).isNull();
        assertThat(redis.calls).isEqualTo(2);
    }
}
