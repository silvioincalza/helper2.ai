package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The daily counter on the real Redis: the count and its expiry are set together. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisCallCounterIT {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    @Autowired
    StringRedisTemplate redis;

    @Test
    void theFirstCallOfADayCreatesTheCounterWithItsExpiry() {
        String name = "test-" + UUID.randomUUID();
        RedisCallCounter counter = new RedisCallCounter(redis, Clock.systemUTC(), Duration.ofSeconds(30));

        assertThat(counter.increment(name, DAY)).isEqualTo(1);
        assertThat(counter.increment(name, DAY)).isEqualTo(2);
        assertThat(counter.increment(name, DAY.plusDays(1))).as("a new day, a new counter").isEqualTo(1);

        long ttl = redis.getExpire(RedisCallCounter.key(name, DAY), TimeUnit.SECONDS);
        assertThat(ttl).isBetween(Duration.ofDays(2).minusMinutes(1).toSeconds(), Duration.ofDays(2).toSeconds());
    }
}
