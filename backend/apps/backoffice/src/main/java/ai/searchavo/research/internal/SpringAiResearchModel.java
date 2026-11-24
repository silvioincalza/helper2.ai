package ai.searchavo.research.internal;

import ai.searchavo.platform.llm.BudgetedChat;
import ai.searchavo.platform.llm.CallOptions;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.LlmPolicies;
import ai.searchavo.platform.llm.ModelAnswers;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.llm.ModelErrors;
import ai.searchavo.platform.resilience.CallRefused;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.research.ResearchAnswer;
import ai.searchavo.research.ResearchModel;
import ai.searchavo.research.ResearchTool;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

class SpringAiResearchModel implements ResearchModel {

  private static final String STEP = "research";

  static final String ANSWER_SCHEMA =
      new BeanOutputConverter<>(ResearchAnswer.class).getJsonSchema();

  private final ChatModel chat;
  private final BudgetedChat.Call call;
  private final int maxTokens;
  private final BudgetedChat calls;
  private final JsonMapper json;
  private final DailyBudget budget;
  private final boolean nativeSchema;

  SpringAiResearchModel(
      ChatModel chat,
      String model,
      String instructions,
      int maxTokens,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience,
      JsonMapper json,
      boolean nativeSchema) {
    this.chat = chat;
    this.call = new BudgetedChat.Call(STEP, model, ModelCallMetrics.promptVersion(instructions));
    this.maxTokens = maxTokens;
    this.calls = new BudgetedChat(LlmPolicies.CHAT, budget, metrics, resilience);
    this.json = json;
    this.budget = budget;
    this.nativeSchema = nativeSchema;
  }

  @Override
  public int callsLeftToday() {
    return budget.remaining();
  }

  @Override
  public String model() {
    return call.model();
  }

  @Override
  public Turn next(List<ResearchModel.Message> conversation, List<ResearchTool> tools) {
    Prompt prompt =
        new Prompt(messages(conversation), options(call.model(), maxTokens, tools, nativeSchema));
    ChatResponse response = call(prompt);
    if (response == null || response.getResult() == null) {
      return new Turn("", List.of(), null, "empty answer", 0, 0);
    }
    AssistantMessage out = response.getResult().getOutput();
    String text = out.getText() == null ? "" : out.getText();
    Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
    int prompted = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
    int completed =
        usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    List<ToolCall> toolCalls =
        out.getToolCalls().stream()
            .map(c -> new ToolCall(c.id(), c.name(), c.arguments() == null ? "{}" : c.arguments()))
            .toList();
    if (!toolCalls.isEmpty()) {
      return new Turn(text, toolCalls, null, null, prompted, completed);
    }
    String jsonText = ModelAnswers.jsonObject(text).orElse(null);
    if (jsonText == null) {
      return new Turn(text, List.of(), null, "no JSON object in the answer", prompted, completed);
    }
    try {
      return new Turn(
          text,
          List.of(),
          json.readValue(jsonText, ResearchAnswer.class),
          null,
          prompted,
          completed);
    } catch (RuntimeException e) {
      return new Turn(
          text,
          List.of(),
          null,
          "not the requested JSON: " + e.getClass().getSimpleName(),
          prompted,
          completed);
    }
  }

  static OpenAiChatOptions options(
      String model, int maxTokens, List<ResearchTool> tools, boolean nativeSchema) {
    OpenAiChatOptions.Builder options =
        CallOptions.of(model, maxTokens)
            .toolCallbacks(tools.stream().map(SpringAiResearchModel::callback).toList());
    if (tools.isEmpty() && nativeSchema) {
      options.outputSchema(ANSWER_SCHEMA);
    }
    return options.build();
  }

  private @Nullable ChatResponse call(Prompt prompt) {
    try {
      return calls.response(call, BudgetSpent::new, () -> chat.call(prompt));
    } catch (Unavailable e) {
      throw e;
    } catch (CallRefused e) {
      throw new Unavailable("model call refused: " + e.getMessage());
    } catch (RuntimeException e) {
      if (ModelErrors.isTransient(e)) {
        throw new Unavailable("provider unavailable: " + e.getClass().getSimpleName());
      }
      throw e;
    }
  }

  private static List<org.springframework.ai.chat.messages.Message> messages(
      List<ResearchModel.Message> conversation) {
    List<org.springframework.ai.chat.messages.Message> out = new ArrayList<>();
    for (ResearchModel.Message m : conversation) {
      out.add(
          switch (m) {
            case ResearchModel.Message.Instructions i -> new SystemMessage(i.text());
            case ResearchModel.Message.User u -> new UserMessage(u.text());
            case ResearchModel.Message.Assistant a ->
                AssistantMessage.builder()
                    .content(a.text())
                    .toolCalls(
                        a.calls().stream()
                            .map(c ->
                                new AssistantMessage.ToolCall(
                                    c.id(), "function", c.name(), c.arguments()))
                            .toList())
                    .build();
            case ResearchModel.Message.ToolResult r ->
                ToolResponseMessage.builder()
                    .responses(
                        List.of(
                            new ToolResponseMessage.ToolResponse(r.callId(), r.name(), r.text())))
                    .build();
          });
    }
    return out;
  }

  private static ToolCallback callback(ResearchTool tool) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name(tool.name())
            .description(tool.description())
            .inputSchema(tool.parameters())
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        throw new IllegalStateException("research runs its tools itself");
      }
    };
  }
}
