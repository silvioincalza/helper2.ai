package ai.helper2.sources.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Lets a collector run less often than the Scout schedule. The Scout's checkpoint is the start of
 * the last complete pass, so "due" means: no complete pass yet, or the interval has passed since
 * it.
 *
 * <p>An interval of whole days counts calendar days (UTC): a 1-day collector is due on any day
 * after its last pass, whatever the time. The daily run starts at the first check after its cron
 * time, so one day's start can be a few minutes earlier than the day before, and a catch-up run can
 * start late in the day; counting hours would skip the next day in both cases. Any other interval
 * (the MCP Registry's hour) counts time.
 */
public record RunInterval(Duration every) {

  private static final Duration DAY = Duration.ofDays(1);

  public RunInterval {
    if (every == null || every.isNegative()) {
      throw new IllegalArgumentException("run interval must be zero or positive");
    }
  }

  public boolean allowsPass(String checkpoint, String resumeCursor, Instant now) {
    return resumeCursor != null || isDue(checkpoint, now);
  }

  public boolean isDue(String checkpoint, Instant now) {
    if (checkpoint == null || every.isZero()) {
      return true;
    }
    try {
      Instant last = Instant.parse(checkpoint);
      return wholeDays() ? isDueByDate(last, now) : !now.isBefore(last.plus(every));
    } catch (DateTimeParseException e) {
      // An unreadable checkpoint must not block collection forever.
      return true;
    }
  }

  private boolean wholeDays() {
    return every.compareTo(DAY) >= 0 && every.toSeconds() % DAY.toSeconds() == 0;
  }

  private boolean isDueByDate(Instant last, Instant now) {
    LocalDate due = utcDate(last).plusDays(every.toDays());
    return !utcDate(now).isBefore(due);
  }

  private static LocalDate utcDate(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate();
  }
}
