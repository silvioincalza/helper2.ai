package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CronRunCalendarTest {

  private final ScoutConfiguration.CronRunCalendar calendar =
      new ScoutConfiguration.CronRunCalendar("0 7 3 * * *");

  @Test
  void aLongRunDoesNotMoveTheNextStart() {
    assertThat(calendar.nextRunAfter(Instant.parse("2026-11-05T04:42:10Z")))
        .isEqualTo(Instant.parse("2026-11-06T03:07:00Z"));
  }

  @Test
  void aRunThatEndedBeforeTheCronTimeIsDueTheSameDay() {
    assertThat(calendar.nextRunAfter(Instant.parse("2026-11-05T01:00:00Z")))
        .isEqualTo(Instant.parse("2026-11-05T03:07:00Z"));
  }

  @Test
  void theCronIsReadInUtc() {
    assertThat(calendar.nextRunAfter(Instant.parse("2026-11-05T03:06:59Z")))
        .isEqualTo(Instant.parse("2026-11-05T03:07:00Z"));
  }

  @Test
  void anUnreadableCronStopsStartup() {
    assertThatThrownBy(() -> new ScoutConfiguration.CronRunCalendar("every day"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
