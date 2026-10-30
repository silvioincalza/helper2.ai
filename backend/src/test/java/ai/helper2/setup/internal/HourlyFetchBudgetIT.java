package ai.helper2.setup.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.setup.internal.RegistryMcpServerSpecsTest.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The hourly fetch budget is one count for every replica, kept in Redis, and a new hour starts a new count. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HourlyFetchBudgetIT {

    @Autowired
    StringRedisTemplate redis;

    private HourlyFetchBudget budget(MutableClock clock, StringRedisTemplate template, Resilience resilience) {
        return new HourlyFetchBudget(3, clock, template, resilience, new SimpleMeterRegistry().counter("refused"));
    }

    @Test
    void replicasShareOneHourlyCount_andANewHourStartsAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2031-01-01T10:00:00Z"));
        HourlyFetchBudget a = budget(clock, redis, Resilience.NONE);
        HourlyFetchBudget b = budget(clock, redis, Resilience.NONE);

        assertThat(a.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isTrue();
        assertThat(a.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isFalse();
        assertThat(redis.getExpire(HourlyFetchBudget.key(clock.instant()), TimeUnit.SECONDS))
            .isBetween(1L, Duration.ofHours(2).toSeconds());

        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThat(b.tryAcquire()).isTrue();
    }

    @Test
    void withRedisRefusedEachReplicaCountsOnItsOwn() {
        MutableClock clock = new MutableClock(Instant.parse("2031-01-02T10:00:00Z"));
        Resilience refusing = new Resilience() {
            @Override
            public <T> T call(String policy, java.util.function.Supplier<T> work) {
                throw new IllegalStateException("circuit open");
            }

            @Override
            public void require(String policy) {
            }
        };
        HourlyFetchBudget a = budget(clock, redis, refusing);

        assertThat(a.tryAcquire()).isTrue();
        assertThat(a.tryAcquire()).isTrue();
        assertThat(a.tryAcquire()).isTrue();
        assertThat(a.tryAcquire()).isFalse();
    }
}
