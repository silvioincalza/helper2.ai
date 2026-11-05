package ai.helper2.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The snapshot each replica applies (ADR 0058): read again only when the refresh interval has
 * passed and the store's version changed; a budget picks up a new limit then.
 */
class RuntimeSettingsTest {

  static final Duration REFRESH = Duration.ofSeconds(20);

  /** A clock the test moves. */
  static final class MovableClock extends Clock {
    Instant now = Instant.parse("2026-11-05T10:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** The store in memory; it can be made to fail. */
  static final class MemoryStore implements RuntimeSettingStore {
    final List<Change> changes = new ArrayList<>();
    int versionReads;
    boolean down;

    void set(RuntimeSetting setting, @Nullable Long value) {
      changes.add(new Change(changes.size() + 1L, setting.key(), value, "1", Instant.EPOCH, null));
    }

    @Override
    public long version() {
      versionReads++;
      if (down) {
        throw new IllegalStateException("store down");
      }
      return changes.size();
    }

    @Override
    public Map<String, Change> newest() {
      Map<String, Change> newest = new LinkedHashMap<>();
      changes.forEach(c -> newest.put(c.name(), c));
      return newest;
    }

    @Override
    public List<Change> history(String name, int limit) {
      return changes.reversed().stream().filter(c -> c.name().equals(name)).limit(limit).toList();
    }

    @Override
    public @Nullable Change append(
        String name,
        @Nullable Long value,
        long expectedVersion,
        String changedBy,
        Instant at,
        @Nullable String reason) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int deleteBefore(Instant cutoff) {
      return 0;
    }
  }

  final MovableClock clock = new MovableClock();
  final MemoryStore store = new MemoryStore();
  final RuntimeSettings runtime = new RuntimeSettings(store, clock, REFRESH);

  @Test
  void theDefaultAppliesUntilAnOverrideIsRead() {
    IntSupplier guide = runtime.declare(RuntimeSetting.GUIDE_DAILY_MODEL_CALLS, 600);
    runtime.tick();
    assertThat(guide.getAsInt()).isEqualTo(600);
    assertThat(runtime.overrideCount()).isZero();

    store.set(RuntimeSetting.GUIDE_DAILY_MODEL_CALLS, 50L);
    assertThat(guide.getAsInt()).as("hot paths read the snapshot only").isEqualTo(600);

    clock.now = clock.now.plus(REFRESH);
    assertThat(runtime.tick()).isTrue();
    assertThat(guide.getAsInt()).isEqualTo(50);
    assertThat(runtime.overrideCount()).isEqualTo(1);
    assertThat(runtime.version()).isEqualTo(1);
  }

  @Test
  void theStoreIsCheckedAtMostOncePerRefreshInterval() {
    runtime.tick();
    int reads = store.versionReads;
    clock.now = clock.now.plus(REFRESH.minusSeconds(1));
    assertThat(runtime.tick()).isFalse();
    assertThat(store.versionReads).as("not due yet").isEqualTo(reads);
    clock.now = clock.now.plusSeconds(1);
    runtime.tick();
    assertThat(store.versionReads).isEqualTo(reads + 1);
  }

  @Test
  void aResetBringsTheDefaultBack() {
    IntSupplier gap = runtime.declare(RuntimeSetting.SCOUT_GAP_DAILY_CALLS, 400);
    store.set(RuntimeSetting.SCOUT_GAP_DAILY_CALLS, 0L);
    runtime.reloadIfChanged();
    assertThat(gap.getAsInt()).as("0 turns the use off").isZero();

    store.set(RuntimeSetting.SCOUT_GAP_DAILY_CALLS, null);
    runtime.reloadIfChanged();
    assertThat(gap.getAsInt()).isEqualTo(400);
    assertThat(runtime.overrideCount()).isZero();
  }

  @Test
  void aCapWithNoDefaultIsNoCapUntilSet() {
    var credits = runtime.declareCap(RuntimeSetting.SEARCH_WEB_GLOBAL_DAILY_CREDITS, null);
    assertThat(credits.get()).isNull();
    store.set(RuntimeSetting.SEARCH_WEB_GLOBAL_DAILY_CREDITS, 120L);
    runtime.reloadIfChanged();
    assertThat(credits.get()).isEqualTo(120L);
  }

  @Test
  void aStoredValueAboveTodaysCodeMaximumAppliesAsTheMaximum() {
    IntSupplier perRun = runtime.declare(RuntimeSetting.SCOUT_MAX_ENRICHMENTS_PER_RUN, 20);
    store.set(RuntimeSetting.SCOUT_MAX_ENRICHMENTS_PER_RUN, 1_000_000L);
    runtime.reloadIfChanged();
    assertThat(perRun.getAsInt()).isEqualTo(RuntimeSetting.SCOUT_MAX_ENRICHMENTS_PER_RUN.max());
  }

  @Test
  void aDefaultAboveTheCodeMaximumStopsStartup() {
    RuntimeSetting s = RuntimeSetting.SEARCH_DAILY_TRANSLATIONS;
    assertThatThrownBy(() -> runtime.declare(s, (int) s.max() + 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("H2_SEARCH_DAILY_TRANSLATIONS");
  }

  @Test
  void aStoreThatCannotAnswerLeavesTheLastSnapshot() {
    IntSupplier guide = runtime.declare(RuntimeSetting.GUIDE_DAILY_MODEL_CALLS, 600);
    store.set(RuntimeSetting.GUIDE_DAILY_MODEL_CALLS, 50L);
    runtime.tick();
    store.down = true;
    clock.now = clock.now.plus(REFRESH);
    assertThatThrownBy(runtime::tick).isInstanceOf(IllegalStateException.class);
    assertThat(guide.getAsInt()).isEqualTo(50);
  }

  @Test
  void aBudgetPicksUpANewLimitAfterTheRefresh() {
    DailyBudget budget =
        new DailyBudget(
            runtime.declare(RuntimeSetting.GUIDE_DAILY_MODEL_CALLS, 2),
            clock,
            "guide",
            CallCounter.NONE);
    assertThat(budget.tryAcquire()).isTrue();
    assertThat(budget.tryAcquire()).isTrue();
    assertThat(budget.tryAcquire()).as("the default, 2, is used up").isFalse();

    store.set(RuntimeSetting.GUIDE_DAILY_MODEL_CALLS, 3L);
    clock.now = clock.now.plus(REFRESH);
    runtime.tick();

    assertThat(budget.limit()).isEqualTo(3);
    assertThat(budget.tryAcquire()).as("one more under the new limit").isTrue();
    assertThat(budget.tryAcquire()).isFalse();
  }
}
