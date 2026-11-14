package ai.helper2.sources.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

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
      return true;
    }
  }

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
