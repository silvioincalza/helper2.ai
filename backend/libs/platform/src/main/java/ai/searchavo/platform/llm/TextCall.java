package ai.searchavo.platform.llm;

import ai.searchavo.platform.resilience.CallRefused;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

public final class TextCall {

  private final ChatClient chat;
  private final BudgetedChat calls;
  private final BudgetedChat.Call call;
  private final String system;
  private final int maxTokens;

  public TextCall(
      ChatModel chatModel,
      BudgetedChat calls,
      String step,
      String model,
      int maxTokens,
      String system) {
    this.chat = ChatClient.builder(chatModel).build();
    this.calls = calls;
    this.call = new BudgetedChat.Call(step, model, ModelCallMetrics.promptVersion(system));
    this.system = system;
    this.maxTokens = maxTokens;
  }

  public String model() {
    return call.model();
  }

  public String promptVersion() {
    return call.promptVersion();
  }

  public @Nullable String answer(
      String user, String budgetSpent, Function<String, ? extends RuntimeException> unavailable) {
    return answerTo(prompt -> prompt.user(user), budgetSpent, unavailable);
  }

  public @Nullable String answer(
      Consumer<ChatClient.PromptUserSpec> user,
      String budgetSpent,
      Function<String, ? extends RuntimeException> unavailable) {
    return answerTo(prompt -> prompt.user(user), budgetSpent, unavailable);
  }

  private @Nullable String answerTo(
      UnaryOperator<ChatClient.ChatClientRequestSpec> user,
      String budgetSpent,
      Function<String, ? extends RuntimeException> unavailable) {
    try {
      return calls.text(
          call,
          () -> unavailable.apply(budgetSpent),
          () ->
              user.apply(
                      chat.prompt().options(CallOptions.of(call.model(), maxTokens)).system(system))
                  .call()
                  .chatResponse());
    } catch (CallRefused e) {
      throw unavailable.apply("model call refused: " + e.policy() + " circuit is " + e.reason());
    }
  }
}
