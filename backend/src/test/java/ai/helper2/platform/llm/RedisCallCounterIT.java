package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.resilience.Resilience;
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

    @Autowired
    Resilience resilience;

    @Test
    void theFirstCallOfADayCreatesTheCounterWithItsExpiry() {
        String name = "test-" + UUID.randomUUID();
        RedisCallCounter counter = new RedisCallCounter(redis, resilience);

        assertThat(counter.increment(name, DAY)).isEqualTo(1);
        assertThat(counter.increment(name, DAY)).isEqualTo(2);
        assertThat(counter.increment(name, DAY.plusDays(1))).as("a new day, a new counter").isEqualTo(1);

        long ttl = redis.getExpire(RedisCallCounter.key(name, DAY), TimeUnit.SECONDS);
        assertThat(ttl).isBetween(Duration.ofDays(2).minusMinutes(1).toSeconds(), Duration.ofDays(2).toSeconds());
    }

    @Test
    void theCurrentCountIsReadWithoutAddingOne() {
        String name = "test-" + UUID.randomUUID();
        RedisCallCounter counter = new RedisCallCounter(redis, resilience);

        assertThat(counter.current(name, DAY)).as("nothing counted yet").isNull();
        counter.increment(name, DAY);
        counter.increment(name, DAY);
        assertThat(counter.current(name, DAY)).isEqualTo(2);
        assertThat(counter.current(name, DAY)).as("reading does not count").isEqualTo(2);
    }

    /** Every replica counts in Redis, so the ledger shows the shared count; a count past the limit shows as the limit. */
    @Test
    void theLedgerShowsTheSharedCountOfEachBudget() {
        java.time.Clock clock = java.time.Clock.fixed(DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneOffset.UTC);
        String name = "test-" + UUID.randomUUID();
        BudgetLedger ledger = new BudgetLedger(new RedisCallCounter(redis, resilience), clock);
        DailyBudget one = new DailyBudget(2, clock, name, ledger);
        DailyBudget other = new DailyBudget(2, clock, name, ledger);

        assertThat(one.tryAcquire()).isTrue();
        assertThat(other.tryAcquire()).isTrue();
        assertThat(one.tryAcquire()).as("the shared count is at the limit").isFalse();
        assertThat(ledger.today()).containsExactly(new BudgetLedger.Reading(name, 2, 2L));
    }
}
