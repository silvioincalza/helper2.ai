package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.helper2.guide.CallTimeLimit;
import ai.helper2.guide.GuideUnavailable;
import ai.helper2.guide.QuestionDeadline;
import ai.helper2.guide.config.ModelStream;
import ai.helper2.guide.internal.FakeProvider.Reply;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

@ParameterizedClass
@EnumSource(ModelStream.class)
class CallTimeOnTheWireTest {

  static final Duration DEADLINE = Duration.ofSeconds(3);
  static final Duration MARGIN = Duration.ofSeconds(1);

  @Parameter ModelStream stream;

  final FakeProvider provider = new FakeProvider().otherwise(Reply.silent());

  @AfterEach
  void stop() {
    provider.close();
  }

  private SpringAiGuideModel model(SpringAiGuideModel.Thinking thinking) {
    return new SpringAiGuideModel(
        provider.chatModel(),
        "test",
        "fast",
        "reasoning",
        true,
        8192,
        thinking,
        new DailyBudget(10, Clock.systemUTC(), "guide"),
        ModelCallMetrics.none(),
        Resilience.NONE,
        new CallTimeLimit(Duration.ofSeconds(60), MARGIN, Duration.ofMillis(200)),
        Clock.systemUTC(),
        stream);
  }

  private record Ended(Throwable failure, Duration after) {}

  private Ended planBeforeTheDeadline(SpringAiGuideModel model) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    long started = System.nanoTime();
    QuestionDeadline.runUntil(
        Clock.systemUTC().instant().plus(DEADLINE),
        () -> failure.set(catchThrowable(() -> model.plan("postgres MCP", null))));
    return new Ended(failure.get(), Duration.ofNanos(System.nanoTime() - started));
  }

  @Test
  void aSilentProviderIsLeftAMarginBeforeTheQuestionsDeadlineThoughTheClientWouldWaitLonger() {
    Ended ended = planBeforeTheDeadline(model(SpringAiGuideModel.Thinking.DEFAULT));

    assertThat(ended.failure())
        .isInstanceOfSatisfying(
            GuideUnavailable.class, e -> assertThat(e.getCategory()).isEqualTo("deadline"));
    assertThat(ended.after()).isBetween(DEADLINE.minus(MARGIN).minusMillis(200), DEADLINE);
    assertThat(provider.bodies).hasSize(1);
  }

  @Test
  void theCallWithoutThinkingAfterACutAnswerAlsoEndsBeforeTheDeadline() {
    provider.then(Reply.cut("{"));

    Ended ended = planBeforeTheDeadline(model(new SpringAiGuideModel.Thinking(512, 0, 0, 0)));

    assertThat(ended.failure())
        .isInstanceOfSatisfying(
            GuideUnavailable.class, e -> assertThat(e.getCategory()).isEqualTo("cut"));
    assertThat(ended.after()).isLessThan(DEADLINE);
    assertThat(provider.bodies).hasSize(2);
    assertThat(provider.bodies.get(1)).contains("\"enable_thinking\":false");
  }
}
