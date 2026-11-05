package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.resilience.Resilience;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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

  @Autowired StringRedisTemplate redis;

  @Autowired Resilience resilience;

  @Test
  void theFirstCallOfADayCreatesTheCounterWithItsExpiry() {
    String name = "test-" + UUID.randomUUID();
    RedisCallCounter counter = new RedisCallCounter(redis, resilience);

    assertThat(counter.increment(name, DAY)).isEqualTo(1);
    assertThat(counter.increment(name, DAY)).isEqualTo(2);
    assertThat(counter.increment(name, DAY.plusDays(1)))
        .as("a new day, a new counter")
        .isEqualTo(1);

    long ttl = redis.getExpire(RedisCallCounter.key(name, DAY), TimeUnit.SECONDS);
    assertThat(ttl)
        .isBetween(Duration.ofDays(2).minusMinutes(1).toSeconds(), Duration.ofDays(2).toSeconds());
  }

  @Test
  void theCurrentCountIsReadWithoutAddingOne() {
    String name = "test-" + UUID.randomUUID();
    RedisCallCounter counter = new RedisCallCounter(redis, resilience);

    assertThat(counter.current(name, DAY))
        .as("no key yet: Redis answered, nothing used")
        .isEqualTo(0);
    counter.increment(name, DAY);
    counter.increment(name, DAY);
    assertThat(counter.current(name, DAY)).isEqualTo(2);
    assertThat(counter.current(name, DAY)).as("reading does not count").isEqualTo(2);
  }

  /**
   * Every replica counts in Redis, so the ledger shows the shared count; a count past the limit
   * shows as the limit.
   */
  @Test
  void theLedgerShowsTheSharedCountOfEachBudget() {
    java.time.Clock clock =
        java.time.Clock.fixed(
            DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneOffset.UTC);
    String name = "test-" + UUID.randomUUID();
    BudgetLedger ledger = new BudgetLedger(new RedisCallCounter(redis, resilience), clock);
    DailyBudget one = new DailyBudget(2, clock, name, ledger);
    DailyBudget other = new DailyBudget(2, clock, name, ledger);

    assertThat(one.tryAcquire()).isTrue();
    assertThat(other.tryAcquire()).isTrue();
    assertThat(one.tryAcquire()).as("the shared count is at the limit").isFalse();
    assertThat(ledger.today()).containsExactly(new BudgetLedger.Reading(name, 2, 2L));
  }

  @Test
  void aCountIsGivenBackOnceAndItsSetExpiresWithTheCount() {
    String name = "test-" + UUID.randomUUID();
    RedisCallCounter counter = new RedisCallCounter(redis, resilience);
    counter.increment(name, DAY);
    counter.increment(name, DAY);

    assertThat(counter.giveBack(name, DAY, "q1", 5)).isEqualTo(CallCounter.GiveBack.GIVEN_BACK);
    assertThat(counter.giveBack(name, DAY, "q1", 5))
        .as("a retry of the same question")
        .isEqualTo(CallCounter.GiveBack.ALREADY_GIVEN_BACK);
    assertThat(counter.current(name, DAY)).isEqualTo(1);

    long ttl = redis.getExpire(RedisCallCounter.givenBackKey(name, DAY), TimeUnit.SECONDS);
    assertThat(ttl)
        .isBetween(Duration.ofDays(2).minusMinutes(1).toSeconds(), Duration.ofDays(2).toSeconds());
  }

  @Test
  void aGiveBackStopsAtTheDailyCapAndNeverGoesBelowZero() {
    String name = "test-" + UUID.randomUUID();
    RedisCallCounter counter = new RedisCallCounter(redis, resilience);
    assertThat(counter.giveBack(name, DAY, "q0", 5))
        .as("nothing counted yet")
        .isEqualTo(CallCounter.GiveBack.NOTHING_COUNTED);
    counter.increment(name, DAY);
    counter.increment(name, DAY);
    counter.increment(name, DAY);

    counter.giveBack(name, DAY, "q1", 2);
    counter.giveBack(name, DAY, "q2", 2);

    assertThat(counter.giveBack(name, DAY, "q3", 2)).isEqualTo(CallCounter.GiveBack.CAP_REACHED);
    assertThat(counter.current(name, DAY)).isEqualTo(1);
    assertThat(redis.hasKey(RedisCallCounter.givenBackKey(name, DAY.plusDays(1))))
        .as("the cap is per day")
        .isFalse();
  }

  /** A double completion on two replicas at once: one gives back, the other finds it done. */
  @Test
  void concurrentGiveBacksOfOneQuestionTakeOneCountOff() throws Exception {
    String name = "test-" + UUID.randomUUID();
    RedisCallCounter counter = new RedisCallCounter(redis, resilience);
    for (int i = 0; i < 3; i++) {
      counter.increment(name, DAY);
    }
    List<Callable<CallCounter.GiveBack>> twins =
        IntStream.range(0, 16)
            .<Callable<CallCounter.GiveBack>>mapToObj(
                i -> () -> counter.giveBack(name, DAY, "q1", 5))
            .toList();

    List<CallCounter.GiveBack> outcomes;
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      outcomes = pool.invokeAll(twins).stream().map(RedisCallCounterIT::get).toList();
    }

    assertThat(outcomes).containsOnlyOnce(CallCounter.GiveBack.GIVEN_BACK);
    assertThat(counter.current(name, DAY)).isEqualTo(2);
  }

  private static CallCounter.GiveBack get(Future<CallCounter.GiveBack> f) {
    try {
      return f.get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
