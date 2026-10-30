package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.testing.MovableClock;
import ai.helper2.testing.TestPolicies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** The shared limit on the real Redis: two RedisRateLimiter instances stand for two replicas. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisRateLimiterIT {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Autowired
    StringRedisTemplate redis;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-27T10:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Resilience resilience = TestPolicies.redis(clock);
    private final String name = "test-" + UUID.randomUUID();

    private RedisRateLimiter replica(StringRedisTemplate template) {
        return new RedisRateLimiter(name, 3, MINUTE, template, resilience, new InMemoryRateLimiter(3, MINUTE, clock), meters);
    }

    private double fallbacks() {
        return meters.counter("helper2.rate-limit.fallback", "limiter", name).count();
    }

    @Test
    void replicasShareOneCountPerClientAndTellHowLongToWait() {
        RedisRateLimiter a = replica(redis);
        RedisRateLimiter b = replica(redis);

        assertThat(a.acquire("203.0.113.7")).isZero();
        assertThat(b.acquire("203.0.113.7")).isZero();
        assertThat(a.acquire("203.0.113.7")).isZero();
        assertThat(b.acquire("203.0.113.7")).isBetween(1L, 60L);
        assertThat(a.acquire("203.0.113.8")).as("another client").isZero();

        String key = RedisRateLimiter.key(name, "203.0.113.7");
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
        assertThat(fallbacks()).isZero();
    }

    @Test
    void anIpv6ClientIsCountedByItsSlash64() {
        RedisRateLimiter a = replica(redis);
        for (int i = 1; i <= 3; i++) {
            assertThat(a.acquire("2001:db8:7:8::" + i)).isZero();
        }
        assertThat(replica(redis).acquire("2001:db8:7:8:ffff::1")).isPositive();
        assertThat(redis.hasKey(RedisRateLimiter.key(name, "2001:db8:7:8::1"))).isTrue();
        assertThat(RedisRateLimiter.key(name, "2001:db8:7:8::1")).endsWith(":20010db800070008::/64");
    }

    @Test
    void whenRedisIsDownEachReplicaCountsInMemoryAndLeavesRedisAloneForAWhile() {
        DownRedis down = new DownRedis();
        RedisRateLimiter a = replica(down);

        for (int i = 0; i < 3; i++) {
            assertThat(a.acquire("198.51.100.1")).isZero();
        }
        assertThat(a.acquire("198.51.100.1")).as("the same limit, in memory").isPositive();
        assertThat(down.calls).isEqualTo(1);
        assertThat(fallbacks()).isEqualTo(4);

        clock.now = clock.now.plusSeconds(31);
        a.acquire("198.51.100.2");
        assertThat(down.calls).as("one trial call once the open window ends").isEqualTo(2);
    }

    private static final class DownRedis extends StringRedisTemplate {
        int calls;

        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            calls++;
            throw new RedisConnectionFailureException("down");
        }
    }
}
