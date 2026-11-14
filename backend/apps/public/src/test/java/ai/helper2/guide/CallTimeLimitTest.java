package ai.helper2.guide;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CallTimeLimitTest {

  static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final CallTimeLimit limit =
      new CallTimeLimit(Duration.ofSeconds(40), Duration.ofSeconds(2), Duration.ofSeconds(10));

  private Optional<Duration> windowWith(Duration left) {
    AtomicReference<Optional<Duration>> window = new AtomicReference<>();
    QuestionDeadline.runUntil(NOW.plus(left), () -> window.set(limit.forACallStartingNow(clock)));
    return window.get();
  }

  @Test
  void withoutAQuestionDeadlineACallGetsItsConfiguredTimeout() {
    assertThat(limit.forACallStartingNow(clock)).contains(Duration.ofSeconds(40));
  }

  @Test
  void aCallWithPlentyOfTimeLeftGetsItsConfiguredTimeout() {
    assertThat(windowWith(Duration.ofSeconds(90))).contains(Duration.ofSeconds(40));
  }

  @Test
  void aCallNearTheDeadlineEndsAMarginBeforeIt() {
    assertThat(windowWith(Duration.ofSeconds(30))).contains(Duration.ofSeconds(28));
  }

  @Test
  void aCallGetsExactlyTheMinimumWhenThatIsAllThatIsLeftBeforeTheMargin() {
    assertThat(windowWith(Duration.ofSeconds(12))).contains(Duration.ofSeconds(10));
  }

  @Test
  void aCallDoesNotStartWithLessThanTheMinimumLeftBeforeTheMargin() {
    assertThat(windowWith(Duration.ofMillis(11_999))).isEmpty();
  }

  @Test
  void aCallDoesNotStartPastTheDeadline() {
    assertThat(windowWith(Duration.ofSeconds(-1))).isEmpty();
  }

  @Test
  void aWindowCutByTheDeadlineIsShorterThanTheConfiguredTimeout() {
    assertThat(limit.shortens(Duration.ofSeconds(28))).isTrue();
    assertThat(limit.shortens(Duration.ofSeconds(40))).isFalse();
  }

  private <T> T with(Duration left, Supplier<T> read) {
    AtomicReference<T> value = new AtomicReference<>();
    QuestionDeadline.runUntil(NOW.plus(left), () -> value.set(read.get()));
    return value.get();
  }

  @Test
  void thinkingMayLastUntilOneMoreCallStillFitsBeforeTheDeadline() {
    assertThat(with(Duration.ofSeconds(30), () -> limit.forThinking(clock)))
        .contains(Duration.ofSeconds(18));
  }

  @Test
  void thinkingHasNoTimeLimitWithoutAQuestionDeadline() {
    assertThat(limit.forThinking(clock)).isEmpty();
  }

  @Test
  void thinkingHasNoTimeLimitWhenOneMoreCallDoesNotFitAnyway() {
    assertThat(with(Duration.ofSeconds(12), () -> limit.forThinking(clock))).isEmpty();
  }

  @Test
  void theCallAfterThinkingGetsWhatIsLeftBeforeTheMarginEvenJustUnderTheMinimum() {
    assertThat(with(Duration.ofMillis(11_990), () -> limit.forTheCallAfterThinking(clock)))
        .contains(Duration.ofMillis(9_990));
  }

  @Test
  void theCallAfterThinkingNeverGetsMoreThanTheModelTimeout() {
    assertThat(with(Duration.ofSeconds(90), () -> limit.forTheCallAfterThinking(clock)))
        .contains(Duration.ofSeconds(40));
    assertThat(limit.forTheCallAfterThinking(clock)).contains(Duration.ofSeconds(40));
  }

  @Test
  void theCallAfterThinkingDoesNotStartInsideTheMargin() {
    assertThat(with(Duration.ofSeconds(2), () -> limit.forTheCallAfterThinking(clock))).isEmpty();
  }

  @Test
  void withNoLimitACallStartsUntilTheDeadlineAndGetsNoTimeoutOfItsOwn() {
    AtomicReference<Optional<Duration>> before = new AtomicReference<>();
    AtomicReference<Optional<Duration>> after = new AtomicReference<>();

    QuestionDeadline.runUntil(
        NOW.plusSeconds(1), () -> before.set(CallTimeLimit.NONE.forACallStartingNow(clock)));
    QuestionDeadline.runUntil(
        NOW.minusSeconds(1), () -> after.set(CallTimeLimit.NONE.forACallStartingNow(clock)));

    assertThat(before.get()).contains(Duration.ZERO);
    assertThat(after.get()).isEmpty();
  }

  @Test
  void aQuestionKnowsWhenItsDeadlineIsOver() {
    AtomicReference<Boolean> running = new AtomicReference<>();
    AtomicReference<Boolean> over = new AtomicReference<>();

    QuestionDeadline.runUntil(
        NOW.plusSeconds(1), () -> running.set(QuestionDeadline.isOver(clock)));
    QuestionDeadline.runUntil(NOW, () -> over.set(QuestionDeadline.isOver(clock)));

    assertThat(running.get()).isFalse();
    assertThat(over.get()).isTrue();
    assertThat(QuestionDeadline.isOver(clock)).as("no question, no deadline").isFalse();
  }
}
