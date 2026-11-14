package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.resilience.Resilience4jPolicies;
import ai.helper2.testing.TestPolicies;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class BudgetedChatTest {

  static final BudgetedChat.Call CALL = new BudgetedChat.Call("step", "model", "v1");

  final AtomicInteger modelCalls = new AtomicInteger();

  static final class Spent extends RuntimeException {}

  ChatResponse reply(String text) {
    modelCalls.incrementAndGet();
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  BudgetedChat chat(int dailyCalls, Resilience resilience) {
    return new BudgetedChat(
        LlmPolicies.CHAT,
        new DailyBudget(dailyCalls, Clock.systemUTC(), "test"),
        ModelCallMetrics.none(),
        resilience);
  }

  @Test
  void aCallWithinTheBudgetReturnsTheModelsText() {
    assertThat(chat(1, Resilience.NONE).text(CALL, Spent::new, () -> reply("answer")))
        .isEqualTo("answer");
    assertThat(modelCalls).hasValue(1);
  }

  @Test
  void aSpentBudgetThrowsTheCallersErrorAndCallsNoModel() {
    BudgetedChat chat = chat(1, Resilience.NONE);
    chat.text(CALL, Spent::new, () -> reply("first"));

    assertThatThrownBy(() -> chat.text(CALL, Spent::new, () -> reply("second")))
        .isInstanceOf(Spent.class);
    assertThat(modelCalls).hasValue(1);
  }

  @Test
  void anOpenCircuitRefusesBeforeTheBudgetIsTaken() {
    DailyBudget budget = new DailyBudget(1, Clock.systemUTC(), "test");
    BudgetedChat chat =
        new BudgetedChat(
            LlmPolicies.CHAT, budget, ModelCallMetrics.none(), TestPolicies.llmChatOpen());

    assertThatThrownBy(() -> chat.text(CALL, Spent::new, () -> reply("answer")))
        .isInstanceOf(CallRefused.class);
    assertThat(modelCalls).hasValue(0);
    assertThat(budget.remaining()).isEqualTo(1);
  }

  @Test
  void anEmptyReplyIsNoText() {
    assertThat(
            chat(1, Resilience.NONE)
                .text(
                    CALL,
                    Spent::new,
                    () -> {
                      modelCalls.incrementAndGet();
                      return null;
                    }))
        .isNull();
  }

  static ChatStream.Answer streamedIn(Duration time) {
    try {
      Thread.sleep(time);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return new ChatStream.Answer("answer", "STOP", 12, null);
  }

  @Test
  void aStreamedCallTakesOneBudgetUnitAndGivesWhatWasReadFromTheWholeAnswer() {
    DailyBudget budget = new DailyBudget(5, Clock.systemUTC(), "test");
    BudgetedChat chat =
        new BudgetedChat(LlmPolicies.CHAT, budget, ModelCallMetrics.none(), Resilience.NONE);

    String read =
        chat.streamed(
            CALL, Spent::new, () -> streamedIn(Duration.ZERO), answer -> answer.text() + "!");

    assertThat(read).isEqualTo("answer!");
    assertThat(budget.usedToday()).isEqualTo(1);
  }

  @Test
  void aSpentBudgetOpensNoStream() {
    BudgetedChat chat = chat(0, Resilience.NONE);

    assertThatThrownBy(() ->
            chat.streamed(
                CALL,
                Spent::new,
                () -> {
                  modelCalls.incrementAndGet();
                  return streamedIn(Duration.ZERO);
                },
                ChatStream.Answer::text))
        .isInstanceOf(Spent.class);
    assertThat(modelCalls).hasValue(0);
  }

  @Test
  void theCircuitTimesTheWholeStreamNotTheTimeToItsFirstChunk() {
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    CircuitBreaker circuit =
        circuits.circuitBreaker(
            LlmPolicies.CHAT,
            CircuitBreakerConfig.custom()
                .slowCallDurationThreshold(Duration.ofMillis(100))
                .build());
    BudgetedChat chat =
        new BudgetedChat(
            LlmPolicies.CHAT,
            new DailyBudget(5, Clock.systemUTC(), "test"),
            ModelCallMetrics.none(),
            new Resilience4jPolicies(
                circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults()));

    chat.streamed(
        CALL, Spent::new, () -> streamedIn(Duration.ofMillis(300)), ChatStream.Answer::text);

    assertThat(circuit.getMetrics().getNumberOfSlowCalls()).isEqualTo(1);
  }

  @Test
  void theTokensAStreamDidNotReportAreCountedAsUnknown() {
    MeterRegistry meters = new SimpleMeterRegistry();
    BudgetedChat chat =
        new BudgetedChat(
            LlmPolicies.CHAT,
            new DailyBudget(5, Clock.systemUTC(), "test"),
            new ModelCallMetrics(ObservationRegistry.NOOP, meters),
            Resilience.NONE);

    chat.streamed(CALL, Spent::new, () -> streamedIn(Duration.ZERO), ChatStream.Answer::text);

    assertThat(meters.get(ModelCallMetrics.TOKENS).tags("type", "prompt").counter().count())
        .isEqualTo(12.0);
    assertThat(
            meters
                .get(ModelCallMetrics.TOKENS_UNKNOWN)
                .tags("type", "completion")
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
