package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.quota.QuotaKeys;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisRateLimiterIT {

  private static final Duration MINUTE = Duration.ofMinutes(1);

  @Autowired StringRedisTemplate redis;

  private final MovableClock clock = new MovableClock(Instant.parse("2026-10-27T10:00:00Z"));
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final Resilience resilience = TestPolicies.redis(clock);
  private final String name = "test-" + UUID.randomUUID();
  private final QuotaKeys keys = new QuotaKeys("test-secret", clock);

  private RedisRateLimiter replica(StringRedisTemplate template) {
    return new RedisRateLimiter(
        name,
        3,
        MINUTE,
        keys,
        template,
        resilience,
        new InMemoryRateLimiter(3, MINUTE, clock),
        meters);
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

    String key = a.key("203.0.113.7");
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
    assertThat(a.key("2001:db8:7:8::1")).isEqualTo(a.key("2001:db8:7:8:ffff::1"));
    assertThat(redis.hasKey(a.key("2001:db8:7:8::1"))).isTrue();
  }

  @Test
  void theKeyNamesTheClientByItsPseudonymOfTheDayNeverByItsAddress() {
    RedisRateLimiter a = replica(redis);
    String today = a.key("203.0.113.7");

    assertThat(today)
        .isEqualTo(RedisRateLimiter.KEY_PREFIX + name + ":" + keys.client("203.0.113.7"))
        .doesNotContain("203.0.113.7");
    assertThat(a.key("2001:db8:7:8::1")).doesNotContain("2001").doesNotContain("20010db8");
    assertThat(a.key("203.0.113.8")).as("another client").isNotEqualTo(today);
    assertThat(a.acquire("203.0.113.7")).isZero();
    assertThat(
            TestcontainersConfiguration.adminRedis()
                .keys(RedisRateLimiter.KEY_PREFIX + name + ":*"))
        .containsExactly(today)
        .noneMatch(k -> k.contains("203.0.113"));

    clock.now = Instant.parse("2026-10-28T00:00:01Z");
    assertThat(a.key("203.0.113.7")).as("a new UTC day, a new key").isNotEqualTo(today);
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
