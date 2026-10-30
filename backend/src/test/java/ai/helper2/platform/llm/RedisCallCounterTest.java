package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.MovableClock;
import ai.helper2.testing.TestPolicies;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** Redis is optional: when it is down the budget counts in memory without waiting on Redis each time. */
class RedisCallCounterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);

    private static final class DownRedis extends StringRedisTemplate {
        int calls;

        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            calls++;
            throw new RedisConnectionFailureException("down");
        }
    }

    @Test
    void afterAFailureRedisIsLeftAloneForAWhile() {
        DownRedis redis = new DownRedis();
        MovableClock clock = new MovableClock(Instant.parse("2026-10-26T10:00:00Z"));
        RedisCallCounter counter = new RedisCallCounter(redis, TestPolicies.redis(clock));

        assertThat(counter.increment("guide", DAY)).isNull();
        assertThat(counter.increment("guide", DAY)).isNull();
        assertThat(redis.calls).isEqualTo(1);

        clock.now = clock.now.plusSeconds(31);
        assertThat(counter.increment("guide", DAY)).isNull();
        assertThat(redis.calls).isEqualTo(2);
    }

    @Test
    void keysAreSeparatePerBudgetAndDay() {
        assertThat(RedisCallCounter.key("guide", DAY)).isEqualTo("h2:v1:budget:guide:2026-10-26");
        assertThat(RedisCallCounter.key("translation", DAY)).isNotEqualTo(RedisCallCounter.key("guide", DAY));
    }
}
