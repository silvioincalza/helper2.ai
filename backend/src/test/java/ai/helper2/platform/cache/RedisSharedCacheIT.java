package ai.helper2.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.testing.MovableClock;
import ai.helper2.testing.TestPolicies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** The shared cache on the real Redis: two RedisSharedCache instances stand for two replicas. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisSharedCacheIT {

    private static final Duration TTL = Duration.ofHours(24);

    @Autowired
    StringRedisTemplate redis;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-31T10:00:00Z"));

    private RedisSharedCache replica(StringRedisTemplate template, Resilience resilience) {
        return new RedisSharedCache("translation", TTL, template, resilience, meters);
    }

    private double count(String result) {
        return meters.counter("helper2.shared.cache", "cache", "translation", "result", result).count();
    }

    @Test
    void whatOneReplicaStoresAnotherReads_forTheTtl() {
        RedisSharedCache a = replica(redis, Resilience.NONE);
        RedisSharedCache b = replica(redis, Resilience.NONE);
        String key = a.key("fast", "v1", "it", UUID.randomUUID().toString());

        assertThat(b.get(key)).isNull();
        a.put(key, "MCP server postgres");

        assertThat(b.get(key)).isEqualTo("MCP server postgres");
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(TTL.minusMinutes(1).toSeconds(), TTL.toSeconds());
        assertThat(count("hit")).isEqualTo(1);
        assertThat(count("miss")).isEqualTo(1);
    }

    @Test
    void anExpiredEntryIsAMiss() throws InterruptedException {
        RedisSharedCache shortLived = new RedisSharedCache("translation", Duration.ofMillis(50), redis, Resilience.NONE, meters);
        String key = shortLived.key("fast", "v1", "it", UUID.randomUUID().toString());
        shortLived.put(key, "MCP server postgres");

        Thread.sleep(200);

        assertThat(shortLived.get(key)).isNull();
    }

    @Test
    void whileRedisIsDownEveryReadMissesAndWritesDoNothing_thenTheCircuitSparesRedis() {
        DownRedis down = new DownRedis();
        RedisSharedCache a = replica(down, TestPolicies.redis(clock));
        String key = a.key("fast", "v1", "it", "server mcp per postgres");

        assertThat(a.get(key)).isNull();
        a.put(key, "MCP server postgres");
        assertThat(a.get(key)).isNull();

        assertThat(down.calls).as("the open circuit refuses the next calls at once").isEqualTo(1);
        assertThat(count("miss")).isEqualTo(2);
    }

    private static final class DownRedis extends StringRedisTemplate {
        int calls;

        @Override
        public ValueOperations<String, String> opsForValue() {
            calls++;
            throw new RedisConnectionFailureException("down");
        }
    }
}
