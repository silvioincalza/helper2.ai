package ai.helper2.guide;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class QuestionDeadline {

  private static final ScopedValue<Instant> END = ScopedValue.newInstance();

  private QuestionDeadline() {}

  public static void runUntil(Instant end, Runnable work) {
    ScopedValue.where(END, end).run(work);
  }

  public static Optional<Duration> left(Clock clock) {
    return END.isBound()
        ? Optional.of(Duration.between(clock.instant(), END.get()))
        : Optional.empty();
  }

  public static boolean isOver(Clock clock) {
    return left(clock).map(left -> !left.isPositive()).orElse(false);
  }
}
