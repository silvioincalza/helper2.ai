package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.search.DailyLimits;
import ai.helper2.search.WebSearchQuota;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageReaderCreditBudgetTest {

  final Clock clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  static final class MapCounter implements QuotaCounter {
    final Map<Counter, Long> counts = new HashMap<>();

    @Override
    public Result take(Counter own, int ownLimit, Counter global, long globalLimit) {
      long used = counts.getOrDefault(global, 0L);
      if (global != null && used >= globalLimit) {
        return new Result(Verdict.GLOBAL_LIMIT_REACHED, 0);
      }
      counts.put(global, used + 1);
      return new Result(Verdict.TAKEN, 0);
    }

    @Override
    public void giveBack(Counter counter) {}

    @Override
    public void add(Counter counter, long amount) {
      counts.merge(counter, amount, Long::sum);
    }

    @Override
    public long used(Counter counter) {
      return counts.getOrDefault(counter, 0L);
    }
  }

  private WebSearchQuota quota(MapCounter counter, long globalCap) {
    return new WebSearchQuota(
        counter, new QuotaKeys("secret", clock), new DailyLimits(), () -> globalCap);
  }

  private double spent() {
    var c = meters.find("helper2.live-web.credits").tag("feature", "scout-evidence").counter();
    return c == null ? 0 : c.count();
  }

  @Test
  void aCreditCountsOnTheGlobalTavilyCounterAndTheMeter() {
    MapCounter counter = new MapCounter();
    WebSearchQuota global = quota(counter, 100);
    var budget = new PageReaderCreditBudget(new DailyBudget(5, clock, "x"), global, meters);

    assertThat(budget.tryTake()).isTrue();
    assertThat(global.creditsToday().getUsedToday()).isEqualTo(1);
    assertThat(spent()).isEqualTo(1);
  }

  @Test
  void theGlobalCapStopsTheScoutToo() {
    var budget =
        new PageReaderCreditBudget(
            new DailyBudget(5, clock, "x"), quota(new MapCounter(), 0), meters);

    assertThat(budget.tryTake()).isFalse();
    assertThat(spent()).isZero();
  }

  @Test
  void theOwnDailyCapComesFirst() {
    MapCounter counter = new MapCounter();
    WebSearchQuota global = quota(counter, 100);
    var budget = new PageReaderCreditBudget(new DailyBudget(1, clock, "x"), global, meters);

    assertThat(budget.tryTake()).isTrue();
    assertThat(budget.tryTake()).isFalse();
    assertThat(global.creditsToday().getUsedToday()).isEqualTo(1);
  }
}
