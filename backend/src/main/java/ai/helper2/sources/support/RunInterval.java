package ai.helper2.sources.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>A staggered interval ({@link #staggered}) spreads collectors with the same interval over its
 * days: a collector that never ran is first due on the UTC day whose number since 1970, modulo the
 * interval's days, is its {@code firstDay}. After its first pass it is due every interval, like any
 * other. Without a first day, a collector that never ran is due at once. The stagger only delays
 * the first try: when that try fails or meets a cap, the Scout keeps the pass open and the
 * collector runs it on the next run, whatever the day (the open pass does not ask this interval).
 */
public record RunInterval(Duration every, @Nullable Integer firstDay) {

  private static final Duration DAY = Duration.ofDays(1);

  public RunInterval {
    if (every == null || every.isNegative()) {
      throw new IllegalArgumentException("run interval must be zero or positive");
    }
    if (firstDay != null && (firstDay < 0 || firstDay >= wholeDaysOf(every))) {
      throw new IllegalArgumentException("the first day must be below the interval's days");
    }
  }

  public RunInterval(Duration every) {
    this(every, null);
  }

  /**
   * An interval whose first pass falls on day {@code slot mod days} (UTC). With the collectors'
   * positions in their list as slots, 34 weekly collectors make 4 or 5 a day; a hash of their ids
   * made 2 to 9. Not staggered when the interval is under two whole days.
   */
  public static RunInterval staggered(Duration every, int slot) {
    long days = wholeDaysOf(every);
    return days < 2
        ? new RunInterval(every)
        : new RunInterval(every, (int) Math.floorMod(slot, days));
  }

  public boolean allowsPass(
      @Nullable String checkpoint, @Nullable String resumeCursor, Instant now) {
    return resumeCursor != null || isDue(checkpoint, now);
  }

  public boolean isDue(@Nullable String checkpoint, Instant now) {
    if (checkpoint == null) {
      return isFirstDay(now);
    }
    if (every.isZero()) {
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

  /**
   * The first UTC day, from [now]'s day on, when a pass is due; null for an interval that is not
   * whole days.
   */
  public @Nullable LocalDate nextDueDay(@Nullable String checkpoint, Instant now) {
    if (!wholeDays()) {
      return null;
    }
    LocalDate today = utcDate(now);
    for (int i = 0; i < every.toDays(); i++) {
      LocalDate day = today.plusDays(i);
      if (isDue(checkpoint, day.atStartOfDay(ZoneOffset.UTC).toInstant())) {
        return day;
      }
    }
    return today.plusDays(every.toDays());
  }

  private boolean isFirstDay(Instant now) {
    return firstDay == null
        || Math.floorMod(utcDate(now).toEpochDay(), wholeDaysOf(every)) == firstDay;
  }

  private boolean wholeDays() {
    return wholeDaysOf(every) > 0;
  }

  /** The whole days of [every], or 0 when it is not a whole number of days. */
  private static long wholeDaysOf(Duration every) {
    return every.compareTo(DAY) >= 0 && every.toSeconds() % DAY.toSeconds() == 0
        ? every.toDays()
        : 0;
  }

  private boolean isDueByDate(Instant last, Instant now) {
    LocalDate due = utcDate(last).plusDays(every.toDays());
    return !utcDate(now).isBefore(due);
  }

  private static LocalDate utcDate(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate();
  }
}
