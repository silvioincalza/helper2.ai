package ai.searchavo.sources.github.scout;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

final class ReadmeBudget {

  private final Clock clock;
  private final Instant deadline;
  private final long maxBytes;
  private long bytes;

  private ReadmeBudget(Clock clock, Instant deadline, long maxBytes) {
    this.clock = clock;
    this.deadline = deadline;
    this.maxBytes = maxBytes;
  }

  static ReadmeBudget startingNow(Clock clock, Duration maxRunTime, long maxBytes) {
    return new ReadmeBudget(clock, clock.instant().plus(maxRunTime), maxBytes);
  }

  @Nullable String spent() {
    if (bytes >= maxBytes) {
      return "size-budget";
    }
    return clock.instant().isBefore(deadline) ? null : "time-budget";
  }

  void read(String readme) {
    bytes += readme.getBytes(StandardCharsets.UTF_8).length;
  }
}
