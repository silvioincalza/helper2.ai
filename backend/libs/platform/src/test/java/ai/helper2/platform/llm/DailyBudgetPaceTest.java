package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DailyBudgetPaceTest {

  private static DailyBudget pacedAt(String instant) {
    Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    return DailyBudget.paced(() -> 600, clock, "guide", CallCounter.NONE, 12);
  }

  private static int takeAll(DailyBudget budget) {
    int taken = 0;
    while (budget.tryAcquire()) {
      taken++;
    }
    return taken;
  }

  @Test
  void anEarlyBurstTakesOnlyTheFirstTwoHoursShare() {
    assertThat(takeAll(pacedAt("2026-11-09T00:30:00Z"))).isEqualTo(50);
  }

  @Test
  void whatIsNotUsedEarlierSpillsIntoLaterHours() {
    assertThat(takeAll(pacedAt("2026-11-09T13:10:00Z"))).isEqualTo(350);
    assertThat(takeAll(pacedAt("2026-11-09T23:59:00Z"))).isEqualTo(600);
  }

  @Test
  void whatIsLeftSoFarIsTheOpenShareMinusWhatWasUsed() {
    DailyBudget budget = pacedAt("2026-11-09T00:30:00Z");
    budget.tryAcquire();

    assertThat(budget.remainingSoFarToday()).isEqualTo(49);
    assertThat(budget.remaining()).isEqualTo(599);
  }

  @Test
  void aSpentPartOfTheDayOpensMoreLaterToday() {
    DailyBudget budget = pacedAt("2026-11-09T00:30:00Z");
    takeAll(budget);

    assertThat(budget.opensMoreToday()).isTrue();
  }

  @Test
  void aSpentDayOpensNothingMoreToday() {
    DailyBudget lastPart = pacedAt("2026-11-09T23:59:00Z");
    takeAll(lastPart);
    Clock clock = Clock.fixed(Instant.parse("2026-11-09T00:30:00Z"), ZoneOffset.UTC);
    DailyBudget unpaced = new DailyBudget(2, clock, "guide");
    takeAll(unpaced);

    assertThat(lastPart.opensMoreToday()).isFalse();
    assertThat(unpaced.opensMoreToday()).isFalse();
    assertThat(new DailyBudget(0, clock, "guide").opensMoreToday()).isFalse();
  }

  private static DailyBudget spentAt(String instant, int paceSlots) {
    Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    DailyBudget budget = DailyBudget.paced(() -> 600, clock, "guide", CallCounter.NONE, paceSlots);
    takeAll(budget);
    return budget;
  }

  @Test
  void aSpentPartOfTheDayOpensAgainWhenTheNextPartStarts() {
    assertThat(spentAt("2026-11-09T00:00:00Z", 12).opensAgainAt())
        .isEqualTo(Instant.parse("2026-11-09T02:00:00Z"));
    assertThat(spentAt("2026-11-09T13:59:59Z", 12).opensAgainAt())
        .isEqualTo(Instant.parse("2026-11-09T14:00:00Z"));
    assertThat(spentAt("2026-11-09T14:00:00Z", 12).opensAgainAt())
        .isEqualTo(Instant.parse("2026-11-09T16:00:00Z"));
  }

  @Test
  void theLastPartOfTheDayOpensAgainAtTheNextUtcMidnight() {
    assertThat(spentAt("2026-11-09T22:00:00Z", 12).opensAgainAt())
        .isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
    assertThat(spentAt("2026-11-09T23:59:59.999Z", 12).opensAgainAt())
        .isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
  }

  @Test
  void anUnpacedBudgetOpensAgainAtTheNextUtcMidnight() {
    assertThat(spentAt("2026-11-09T00:00:00Z", 1).opensAgainAt())
        .isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
    assertThat(spentAt("2026-11-09T13:10:00Z", 1).opensAgainAt())
        .isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
  }

  @Test
  void partsThatDoNotDivideTheDayStartOnTheHourTheirShareOpens() {
    assertThat(spentAt("2026-11-09T04:30:00Z", 5).opensAgainAt())
        .isEqualTo(Instant.parse("2026-11-09T05:00:00Z"));
    assertThat(spentAt("2026-11-09T19:30:00Z", 5).opensAgainAt())
        .isEqualTo(Instant.parse("2026-11-09T20:00:00Z"));
  }

  @Test
  void aCapOfZeroOpensAgainAtTheNextUtcMidnight() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);
    DailyBudget off = DailyBudget.paced(() -> 0, clock, "guide", CallCounter.NONE, 12);

    assertThat(off.opensAgainAt()).isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
  }

  @Test
  void anUnpacedBudgetGivesTheWholeDayAtOnce() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-09T00:30:00Z"), ZoneOffset.UTC);

    assertThat(takeAll(new DailyBudget(600, clock, "guide"))).isEqualTo(600);
  }

  @Test
  void paceSlotsAreOneToTwentyFour() {
    Clock clock = Clock.systemUTC();

    assertThatThrownBy(() -> DailyBudget.paced(() -> 1, clock, "x", CallCounter.NONE, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DailyBudget.paced(() -> 1, clock, "x", CallCounter.NONE, 25))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
