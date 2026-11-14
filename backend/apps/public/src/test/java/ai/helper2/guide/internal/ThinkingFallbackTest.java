package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.helper2.guide.CallTimeLimit;
import ai.helper2.guide.GuideBudgetExhausted;
import ai.helper2.guide.GuideUnavailable;
import ai.helper2.guide.Need;
import ai.helper2.guide.QuestionDeadline;
import ai.helper2.guide.config.ModelStream;
import ai.helper2.guide.internal.SpringAiGuideModel.Thinking;
import ai.helper2.guide.internal.SpringAiGuideModelTest.MovableClock;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

@ParameterizedClass
@EnumSource(ModelStream.class)
class ThinkingFallbackTest {

  @Parameter ModelStream stream;

  static final Thinking ON = new Thinking(512, 512, 512, 512);
  static final Thinking BROWSE_ONLY = new Thinking(0, 0, 0, 512);
  static final Thinking OFF = Thinking.DEFAULT;
  static final Need NEED = new Need("postgres MCP");

  record Step(String name, String answer, Consumer<SpringAiGuideModel> ask) {
    @Override
    public String toString() {
      return name;
    }
  }

  static final Step PLAN =
      new Step("plan", SpringAiGuideModelTest.PLAN, m -> m.plan("postgres MCP", null));
  static final Step CHOOSE =
      new Step("choose", "{\"picks\":[]}", m -> m.choose(NEED, List.of(), List.of()));
  static final Step COMPARE = new Step("compare", "{\"rows\":[]}", m -> m.compare(NEED, List.of()));
  static final Step EXPLAIN =
      new Step("explain", "{\"sentences\":[]}", m -> m.explain(NEED, "pg-mcp", List.of()));
  static final Step VERIFY =
      new Step(
          "verify",
          "{\"verdicts\":[]}",
          m -> m.verifyReasons(NEED, List.of(SpringAiGuideModelTest.CLAIM)));

  static Stream<Step> steps() {
    return Stream.of(PLAN, CHOOSE, COMPARE, EXPLAIN, VERIFY);
  }

  final List<Prompt> prompts = new ArrayList<>();
  final DailyBudget budget = new DailyBudget(10, Clock.systemUTC(), "guide");
  final MeterRegistry meters = new SimpleMeterRegistry();

  static Generation cut(String text) {
    return new Generation(
        new AssistantMessage(text),
        ChatGenerationMetadata.builder().finishReason("length").build());
  }

  static Generation whole(String text) {
    return new Generation(
        new AssistantMessage(text), ChatGenerationMetadata.builder().finishReason("stop").build());
  }

  ChatModel scripted(Generation... replies) {
    List<Generation> left = new ArrayList<>(List.of(replies));
    return eitherWay(prompt -> {
      prompts.add(prompt);
      return new ChatResponse(List.of(left.removeFirst()));
    });
  }

  static ChatModel eitherWay(Function<Prompt, ChatResponse> reply) {
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        return reply.apply(prompt);
      }

      @Override
      public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> Flux.just(reply.apply(prompt)));
      }
    };
  }

  SpringAiGuideModel model(
      ChatModel chat,
      Thinking thinking,
      DailyBudget budget,
      Resilience resilience,
      CallTimeLimit timeLimit,
      Clock clock) {
    return new SpringAiGuideModel(
        chat,
        "fake",
        "fast",
        "reasoning",
        true,
        100,
        thinking,
        budget,
        new ModelCallMetrics(ObservationRegistry.NOOP, meters),
        resilience,
        timeLimit,
        clock,
        stream);
  }

  SpringAiGuideModel model(Thinking thinking, Resilience resilience, Generation... replies) {
    return model(
        scripted(replies), thinking, budget, resilience, CallTimeLimit.NONE, Clock.systemUTC());
  }

  SpringAiGuideModel model(Thinking thinking, Generation... replies) {
    return model(thinking, Resilience.NONE, replies);
  }

  double fallbacks(String step) {
    var counter =
        meters
            .find(ModelCallMetrics.THINKING_FALLBACK)
            .tags("step", step, "reason", "cut")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @ParameterizedTest
  @MethodSource("steps")
  void aStepCutWhileThinkingIsAskedOnceMoreWithThinkingOff(Step step) {
    SpringAiGuideModel model = model(ON, cut("{\"half"), whole(step.answer()));

    step.ask().accept(model);

    assertThat(prompts).hasSize(2);
    assertThat(prompts.get(1).getContents()).isEqualTo(prompts.get(0).getContents());
    assertThat(budget.usedToday()).isEqualTo(2);
    assertThat(fallbacks(step.name())).isEqualTo(1.0);
  }

  @ParameterizedTest
  @MethodSource("steps")
  void aStepCutWithThinkingOffIsNotAskedAgain(Step step) {
    SpringAiGuideModel model = model(OFF, cut("{\"half"), whole(step.answer()));

    Throwable failure = catchThrowable(() -> step.ask().accept(model));

    assertThat(failure)
        .isInstanceOfSatisfying(
            GuideUnavailable.class, e -> assertThat(e.getCategory()).isEqualTo("cut"));
    assertThat(prompts).hasSize(1);
    assertThat(budget.usedToday()).isEqualTo(1);
    assertThat(fallbacks(step.name())).isZero();
  }

  @Test
  void theAnswerOfTheCallWithoutThinkingIsTheOneUsed() {
    SpringAiGuideModel model = model(ON, cut(""), whole(SpringAiGuideModelTest.PLAN));

    assertThat(model.plan("postgres MCP", null).getSearchQuery()).isEqualTo("MCP server postgres");
  }

  @Test
  void theChooserThatThinksOnlyForOneQuestionGetsTheSameSecondCall() {
    SpringAiGuideModel model = model(BROWSE_ONLY, cut("{\"picks\":[{"), whole("{\"picks\":[]}"));

    model.choose(NEED, List.of(), List.of(), true);

    assertThat(prompts).hasSize(2);
    assertThat(fallbacks("choose")).isEqualTo(1.0);
  }

  @Test
  void aSecondCutEndsTheStep() {
    SpringAiGuideModel model = model(ON, cut("{\"half"), cut("{\"half"), whole("{}"));

    Throwable failure = catchThrowable(() -> model.plan("postgres MCP", null));

    assertThat(failure)
        .isInstanceOfSatisfying(
            GuideUnavailable.class, e -> assertThat(e.getCategory()).isEqualTo("cut"));
    assertThat(prompts).hasSize(2);
    assertThat(budget.usedToday()).isEqualTo(2);
  }

  @Test
  void theCallWithoutThinkingIsOneCallAndIsNotRepeatedWhenMalformed() {
    SpringAiGuideModel model =
        model(ON, cut("{\"half"), whole("not json"), whole(SpringAiGuideModelTest.PLAN));

    Throwable failure = catchThrowable(() -> model.plan("postgres MCP", null));

    assertThat(failure)
        .isInstanceOfSatisfying(
            GuideUnavailable.class, e -> assertThat(e.getCategory()).isEqualTo("parse_error"));
    assertThat(prompts).hasSize(2);
  }

  @Test
  void aSpentBudgetStopsTheCallWithoutThinking() {
    DailyBudget one = new DailyBudget(1, Clock.systemUTC(), "guide");
    SpringAiGuideModel model =
        model(
            scripted(cut("{\"half"), whole(SpringAiGuideModelTest.PLAN)),
            ON,
            one,
            Resilience.NONE,
            CallTimeLimit.NONE,
            Clock.systemUTC());

    Throwable failure = catchThrowable(() -> model.plan("postgres MCP", null));

    assertThat(failure).isInstanceOf(GuideBudgetExhausted.class);
    assertThat(prompts).hasSize(1);
  }

  @Test
  void theCallWithoutThinkingStartsOnlyWhenTheQuestionHasTimeLeftForIt() {
    MovableClock clock = new MovableClock();
    ChatModel slow =
        eitherWay(prompt -> {
          prompts.add(prompt);
          clock.advance(Duration.ofSeconds(30));
          return new ChatResponse(List.of(cut("{\"half")));
        });
    SpringAiGuideModel model =
        model(
            slow,
            ON,
            new DailyBudget(10, clock, "guide"),
            Resilience.NONE,
            new CallTimeLimit(
                Duration.ofSeconds(40), Duration.ofSeconds(2), Duration.ofSeconds(10)),
            clock);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    QuestionDeadline.runUntil(
        clock.instant().plusSeconds(41),
        () -> failure.set(catchThrowable(() -> model.plan("q", null))));

    assertThat(failure.get())
        .isInstanceOfSatisfying(
            GuideUnavailable.class, e -> assertThat(e.getCategory()).isEqualTo("cut"));
    assertThat(prompts).hasSize(1);
    assertThat(fallbacks("plan")).isZero();
  }

  @Test
  void aCutAnswerIsNotAFailureForTheCircuit() {
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    CircuitBreaker circuit =
        circuits.circuitBreaker(
            LlmPolicies.GUIDE,
            CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(50)
                .ignoreException(new ModelErrors.NotFromProvider())
                .build());
    Resilience resilience =
        new Resilience4jPolicies(
            circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults());
    SpringAiGuideModel model =
        model(ON, resilience, cut("{\"half"), whole(SpringAiGuideModelTest.PLAN));

    model.plan("postgres MCP", null);

    assertThat(prompts).hasSize(2);
    assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
    assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }
}
