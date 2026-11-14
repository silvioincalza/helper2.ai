package ai.helper2.platform.llm;

import java.util.Map;
import org.springframework.ai.openai.OpenAiChatOptions;

public final class CallOptions {

  static final String TEMPLATE_KWARGS = "chat_template_kwargs";
  static final String THINKING_BUDGET = "thinking_token_budget";

  private CallOptions() {}

  public static OpenAiChatOptions.Builder of(String model, Integer answerTokens) {
    OpenAiChatOptions.Builder builder = answering(model);
    if (answerTokens != null) {
      builder.maxTokens(answerTokens);
    }
    builder.extraBody(Map.of(TEMPLATE_KWARGS, Map.of("enable_thinking", false)));
    return builder;
  }

  public static OpenAiChatOptions.Builder thinking(
      String model, Integer answerTokens, int thinkingTokens) {
    if (thinkingTokens <= 0) {
      return of(model, answerTokens);
    }
    OpenAiChatOptions.Builder builder = answering(model);
    if (answerTokens != null) {
      builder.maxTokens(answerTokens + thinkingTokens);
    }
    builder.extraBody(
        Map.of(TEMPLATE_KWARGS, Map.of("enable_thinking", true), THINKING_BUDGET, thinkingTokens));
    return builder;
  }

  private static OpenAiChatOptions.Builder answering(String model) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model id required");
    }
    OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
    builder.temperature(0.0);
    builder.model(model);
    return builder;
  }
}
