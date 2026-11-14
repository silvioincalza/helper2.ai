package ai.helper2.platform.llm;

import ai.helper2.platform.resilience.Resilience;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;

public final class BudgetedChat {

  private final String policy;
  private final DailyBudget budget;
  private final ModelCallMetrics metrics;
  private final Resilience resilience;

  public BudgetedChat(
      String policy, DailyBudget budget, ModelCallMetrics metrics, Resilience resilience) {
    resilience.require(policy);
    this.policy = policy;
    this.budget = budget;
    this.metrics = metrics;
    this.resilience = resilience;
  }

  public record Call(String step, String model, String promptVersion) {}

  public @Nullable String text(
      Call call, Supplier<? extends RuntimeException> budgetSpent, Supplier<ChatResponse> chat) {
    return run(
        call,
        budgetSpent,
        () -> {
          ChatResponse response = chat.get();
          metrics.tokens(call.step(), call.promptVersion(), response);
          return textOf(response);
        });
  }

  public <T> @Nullable T entity(
      Call call,
      Supplier<? extends RuntimeException> budgetSpent,
      Supplier<ResponseEntity<ChatResponse, T>> chat) {
    return run(
        call,
        budgetSpent,
        () -> {
          ResponseEntity<ChatResponse, T> response = chat.get();
          metrics.tokens(call.step(), call.promptVersion(), response.response());
          return response.entity();
        });
  }

  public <T> @Nullable T streamed(
      Call call,
      Supplier<? extends RuntimeException> budgetSpent,
      Supplier<ChatStream.Answer> wholeStream,
      Function<ChatStream.Answer, T> read) {
    return run(
        call,
        budgetSpent,
        () -> {
          ChatStream.Answer answer = wholeStream.get();
          metrics.tokens(
              call.step(), call.promptVersion(), answer.promptTokens(), answer.completionTokens());
          return read.apply(answer);
        });
  }

  private <T> T run(Call call, Supplier<? extends RuntimeException> budgetSpent, Supplier<T> work) {
    return resilience.call(
        policy,
        () -> {
          if (!budget.tryAcquire()) {
            throw budgetSpent.get();
          }
          return metrics.observe(call.step(), call.model(), call.promptVersion(), work);
        });
  }

  private static @Nullable String textOf(@Nullable ChatResponse response) {
    return response == null || response.getResult() == null
        ? null
        : response.getResult().getOutput().getText();
  }
}
