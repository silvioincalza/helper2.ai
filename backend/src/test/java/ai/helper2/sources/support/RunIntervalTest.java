package ai.helper2.sources.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunIntervalTest {

  final Instant now = Instant.parse("2026-10-22T10:00:00Z");
  final RunInterval fiveDays = new RunInterval(Duration.ofDays(5));

  @Test
  void firstRunIsAlwaysDue() {
    assertThat(fiveDays.isDue(null, now)).isTrue();
  }

  @Test
  void dueOnlyOnceTheIntervalHasPassedSinceTheLastCompletePass() {
    assertThat(fiveDays.isDue("2026-10-18T10:00:00Z", now)).isFalse();
    assertThat(fiveDays.isDue("2026-10-17T10:00:00Z", now)).isTrue();
    assertThat(fiveDays.isDue("2026-10-16T09:00:00Z", now)).isTrue();
  }

  @Test
  void aDailyCollectorIsDueOnAnyLaterDayWhateverTheTime() {
    RunInterval daily = new RunInterval(Duration.ofDays(1));
    // Yesterday the run started at 10:08 (first check after the cron time); today at 10:00.
    assertThat(daily.isDue("2026-10-21T10:08:00Z", now)).isTrue();
    // A catch-up run yesterday evening: today's run still collects.
    assertThat(daily.isDue("2026-10-21T23:50:00Z", now)).isTrue();
    // Already ran today.
    assertThat(daily.isDue("2026-10-22T00:10:00Z", now)).isFalse();
  }

  @Test
  void aWeeklyCollectorCountsCalendarDays() {
    RunInterval weekly = new RunInterval(Duration.ofDays(7));
    assertThat(weekly.isDue("2026-10-15T23:59:00Z", now)).isTrue();
    assertThat(weekly.isDue("2026-10-16T00:00:00Z", now)).isFalse();
  }

  @Test
  void anIntervalThatIsNotWholeDaysCountsTime() {
    RunInterval hourly = new RunInterval(Duration.ofHours(1));
    assertThat(hourly.isDue("2026-10-22T09:00:00Z", now)).isTrue();
    assertThat(hourly.isDue("2026-10-22T09:01:00Z", now)).isFalse();
    RunInterval dayAndAHalf = new RunInterval(Duration.ofHours(36));
    assertThat(dayAndAHalf.isDue("2026-10-21T00:00:00Z", now)).isFalse();
  }

  @Test
  void anUnreadableCheckpointDoesNotBlockCollectionForever() {
    assertThat(fiveDays.isDue("not-a-date", now)).isTrue();
  }

  @Test
  void zeroMeansEveryScheduledRun() {
    assertThat(new RunInterval(Duration.ZERO).isDue("2026-10-22T09:59:00Z", now)).isTrue();
  }

  @Test
  void negativeIntervalIsRejected() {
    assertThatThrownBy(() -> new RunInterval(Duration.ofDays(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
