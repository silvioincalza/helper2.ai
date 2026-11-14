package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.helper2.guide.CallTimeLimit;
import ai.helper2.guide.GuideProgress;
import ai.helper2.guide.GuideSearchPlan;
import ai.helper2.guide.GuideUnavailable;
import ai.helper2.guide.ModelActivity;
import ai.helper2.guide.ModelStep;
import ai.helper2.guide.QuestionDeadline;
import ai.helper2.guide.config.ModelStream;
import ai.helper2.guide.internal.FakeProvider.Reply;
import ai.helper2.guide.internal.FakeProvider.Usage;
import ai.helper2.guide.internal.SpringAiGuideModel.Thinking;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.llm.ModelErrors;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.resilience.Resilience4jPolicies;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StreamOnTheWireTest {

  static final Duration DEADLINE = Duration.ofSeconds(3);
  static final Duration MARGIN = Duration.ofMillis(300);
  static final Duration MINIMUM = Duration.ofSeconds(1);
  static final Duration SOON = Duration.ofMillis(700);
  static final Thinking PLAN_THINKS = new Thinking(512, 0, 0, 0);

  final FakeProvider provider = new FakeProvider();
  final MeterRegistry meters = new SimpleMeterRegistry();
  final DailyBudget budget = new DailyBudget(10, Clock.systemUTC(), "guide");
  final List<String> events = new CopyOnWriteArrayList<>();
  final GuideProgress progress =
      new GuideProgress() {
        @Override
        public void reasoning(ModelStep step) {
          events.add("reasoning " + step.getLabel());
        }

        @Override
        public void writing(ModelStep step) {
          events.add("writing " + step.getLabel());
        }
      };

  @AfterEach
  void stop() {
    provider.close();
  }

  private SpringAiGuideModel model(
      Thinking thinking, ModelStream stream, boolean nativeSchema, Resilience resilience) {
    return new SpringAiGuideModel(
        provider.chatModel(),
        "test",
        "fast",
        "reasoning",
        nativeSchema,
        8192,
        thinking,
        budget,
        new ModelCallMetrics(ObservationRegistry.NOOP, meters),
        resilience,
        new CallTimeLimit(Duration.ofSeconds(60), MARGIN, MINIMUM),
        Clock.systemUTC(),
        stream);
  }

  private SpringAiGuideModel streaming(Thinking thinking) {
    return model(thinking, ModelStream.ON, true, Resilience.NONE);
  }

  private record Ended(GuideSearchPlan plan, Throwable failure, Duration after) {
    String category() {
      return ((GuideUnavailable) failure).getCategory();
    }
  }

  private Ended planBeforeTheDeadline(SpringAiGuideModel model) {
    AtomicReference<GuideSearchPlan> plan = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    long started = System.nanoTime();
    QuestionDeadline.runUntil(
        Clock.systemUTC().instant().plus(DEADLINE),
        () -> failure.set(catchThrowable(() -> plan.set(model.plan("postgres MCP", null)))));
    return new Ended(plan.get(), failure.get(), Duration.ofNanos(System.nanoTime() - started));
  }

  private double count(String meter, String... tags) {
    Counter counter = meters.find(meter).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void theStreamedRequestIsTheBlockingRequestPlusTheStreamFlags(boolean nativeSchema) {
    model(PLAN_THINKS, ModelStream.OFF, nativeSchema, Resilience.NONE).plan("postgres MCP", null);
    model(PLAN_THINKS, ModelStream.ON, nativeSchema, Resilience.NONE).plan("postgres MCP", null);

    JsonMapper json = JsonMapper.builder().build();
    ObjectNode blocking = (ObjectNode) json.readTree(provider.bodies.get(0));
    ObjectNode streamed = (ObjectNode) json.readTree(provider.bodies.get(1));
    assertThat(streamed.path("stream").asBoolean()).isTrue();
    assertThat(streamed.path("stream_options").path("include_usage").asBoolean()).isTrue();
    assertThat(streamed.path("stream_options").has("include_obfuscation")).isFalse();
    streamed.remove(List.of("stream", "stream_options"));
    assertThat(streamed).isEqualTo(blocking);
  }

  @Test
  void howACallIsReadIsNotPartOfTheConfigurationTheAnswerCacheIsKeyedBy() {
    assertThat(model(PLAN_THINKS, ModelStream.ON, true, Resilience.NONE).getConfigFingerprint())
        .isEqualTo(
            model(PLAN_THINKS, ModelStream.OFF, true, Resilience.NONE).getConfigFingerprint());
  }

  @Test
  void aStreamedAnswerIsReadWholeAndSaysOnceWhenItReasonsAndWhenItWrites() {
    provider.then(Reply.answer(FakeProvider.PLAN).afterThinking("Let me think about it"));

    GuideSearchPlan plan =
        ModelActivity.reportedTo(progress, () -> streaming(PLAN_THINKS).plan("postgres MCP", null));

    assertThat(plan.getSearchQuery()).isEqualTo("MCP server postgres");
    assertThat(events).containsExactly("reasoning plan", "writing plan");
  }

  @Test
  void aMalformedStreamedAnswerIsAskedOnceMoreAndSaysWritingOnce() {
    provider.then(Reply.answer("not json"));

    GuideSearchPlan plan =
        ModelActivity.reportedTo(
            progress, () -> streaming(Thinking.DEFAULT).plan("postgres MCP", null));

    assertThat(plan.getSearchQuery()).isEqualTo("MCP server postgres");
    assertThat(provider.bodies).hasSize(2);
    assertThat(events).containsExactly("writing plan");
  }

  @Test
  void aBlockingCallSaysNothingAboutReasoningOrWriting() {
    ModelActivity.reportedTo(
        progress,
        () ->
            model(Thinking.DEFAULT, ModelStream.OFF, true, Resilience.NONE)
                .plan("postgres MCP", null));

    assertThat(events).isEmpty();
  }

  @Test
  void aStreamStillOpenWhenTheQuestionsTimeForItEndsIsClosedAtOnce() {
    provider.then(Reply.stallsAfter("{\"searchQuery\":\"MCP"));

    Ended ended = planBeforeTheDeadline(streaming(Thinking.DEFAULT));

    Duration window = DEADLINE.minus(MARGIN);
    assertThat(ended.category()).isEqualTo("deadline");
    assertThat(ended.after()).isBetween(window.minusMillis(200), DEADLINE);
    assertThat(provider.bodies).hasSize(1);
    assertThat(provider.closedByTheClientAfter(1))
        .singleElement()
        .satisfies(closed -> assertThat(closed).isLessThan(window.plus(SOON)));
  }

  @Test
  void anInterruptedQuestionClosesItsStreamAtOnceAndFailsAsCancelled() throws Exception {
    provider.then(Reply.stallsAfter("{\"searchQuery\":\"MCP"));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<Boolean> interruptKept = new AtomicReference<>();
    Thread worker =
        Thread.ofVirtual()
            .start(() -> {
              failure.set(
                  catchThrowable(() -> streaming(Thinking.DEFAULT).plan("postgres MCP", null)));
              interruptKept.set(Thread.currentThread().isInterrupted());
            });

    Thread.sleep(SOON);
    worker.interrupt();
    worker.join(Duration.ofSeconds(5));

    assertThat(failure.get())
        .isInstanceOfSatisfying(
            GuideUnavailable.class, e -> assertThat(e.getCategory()).isEqualTo("cancelled"));
    assertThat(interruptKept.get()).isTrue();
    assertThat(provider.closedByTheClientAfter(1))
        .singleElement()
        .satisfies(closed -> assertThat(closed).isLessThan(SOON.multipliedBy(2)));
  }

  @Test
  void anErrorEventAfterA200IsAFailureOfTheProviderForTheCircuitAndIsNotAskedAgain() {
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    CircuitBreaker circuit =
        circuits.circuitBreaker(
            LlmPolicies.GUIDE,
            CircuitBreakerConfig.custom()
                .ignoreException(new ModelErrors.NotFromProvider())
                .build());
    Resilience resilience =
        new Resilience4jPolicies(
            circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults());
    provider.then(Reply.errorEventAfter("{\"searchQuery\":\"MCP"));

    Throwable failure =
        catchThrowable(() ->
            model(Thinking.DEFAULT, ModelStream.ON, true, resilience).plan("postgres MCP", null));

    assertThat(failure)
        .isInstanceOfSatisfying(
            GuideUnavailable.class, e -> assertThat(e.getCategory()).isEqualTo("unavailable"));
    assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    assertThat(provider.bodies).hasSize(1);
  }

  @Test
  void thinkingThatLeavesNoTimeForTheAnswerIsStoppedAndTheStepIsAskedAgainWithoutThinking() {
    provider.then(Reply.keepsThinking());

    Ended ended = planBeforeTheDeadline(streaming(PLAN_THINKS));

    Duration thinkingTime = DEADLINE.minus(MARGIN).minus(MINIMUM);
    assertThat(ended.failure()).isNull();
    assertThat(ended.plan().getSearchQuery()).isEqualTo("MCP server postgres");
    assertThat(ended.after()).isBetween(thinkingTime.minusMillis(200), DEADLINE);
    assertThat(provider.bodies).hasSize(2);
    assertThat(provider.bodies.get(1)).contains("\"enable_thinking\":false");
    assertThat(provider.closedByTheClientAfter(1)).hasSize(1);
    assertThat(budget.usedToday()).isEqualTo(2);
    assertThat(count(ModelCallMetrics.THINKING_FALLBACK, "step", "plan", "reason", "time"))
        .isEqualTo(1.0);
  }

  @Test
  void thinkingIsNotStoppedOnceTheAnswerTextHasStarted() {
    provider.then(
        Reply.stallsAfter("{\"searchQuery\":\"MCP").afterThinking("Let me think about it"));

    Ended ended = planBeforeTheDeadline(streaming(PLAN_THINKS));

    assertThat(ended.category()).isEqualTo("deadline");
    assertThat(provider.bodies).hasSize(1);
    assertThat(count(ModelCallMetrics.THINKING_FALLBACK, "step", "plan", "reason", "time"))
        .isZero();
  }

  @Test
  void theTokensOfAStreamAreCountedFromItsLastChunk() {
    streaming(Thinking.DEFAULT).plan("postgres MCP", null);

    assertThat(count(ModelCallMetrics.TOKENS, "step", "plan", "type", "prompt")).isEqualTo(7.0);
    assertThat(count(ModelCallMetrics.TOKENS, "step", "plan", "type", "completion")).isEqualTo(5.0);
    assertThat(count(ModelCallMetrics.TOKENS_UNKNOWN, "step", "plan")).isZero();
  }

  @Test
  void aStreamThatReportsOnlyItsPromptTokensCountsItsCompletionAsUnknown() {
    provider.then(Reply.answer(FakeProvider.PLAN).reporting(Usage.PROMPT_ONLY_AT_THE_START));

    streaming(Thinking.DEFAULT).plan("postgres MCP", null);

    assertThat(count(ModelCallMetrics.TOKENS, "step", "plan", "type", "prompt")).isEqualTo(7.0);
    assertThat(count(ModelCallMetrics.TOKENS, "step", "plan", "type", "completion")).isZero();
    assertThat(count(ModelCallMetrics.TOKENS_UNKNOWN, "step", "plan", "type", "completion"))
        .isEqualTo(1.0);
  }
}
