package ai.helper2.guide;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

public record CallTimeLimit(Duration timeout, Duration margin, Duration minimum) {

  public static final CallTimeLimit NONE =
      new CallTimeLimit(Duration.ZERO, Duration.ZERO, Duration.ZERO);

  public Optional<Duration> forACallStartingNow(Clock clock) {
    Optional<Duration> left = QuestionDeadline.left(clock);
    if (left.isEmpty()) {
      return Optional.of(timeout);
    }
    Duration usable = left.get().minus(margin);
    if (usable.compareTo(minimum) < 0) {
      return Optional.empty();
    }
    return Optional.of(usable.compareTo(timeout) < 0 ? usable : timeout);
  }

  public Optional<Duration> forThinking(Clock clock) {
    return QuestionDeadline.left(clock)
        .map(left -> left.minus(margin).minus(minimum))
        .filter(Duration::isPositive);
  }

  public Optional<Duration> forTheCallAfterThinking(Clock clock) {
    Optional<Duration> left = QuestionDeadline.left(clock);
    if (left.isEmpty()) {
      return Optional.of(timeout);
    }
    Duration usable = left.get().minus(margin);
    if (!usable.isPositive()) {
      return Optional.empty();
    }
    return Optional.of(timeout.isPositive() && timeout.compareTo(usable) < 0 ? timeout : usable);
  }

  public boolean shortens(Duration window) {
    return window.compareTo(timeout) < 0;
  }
}
