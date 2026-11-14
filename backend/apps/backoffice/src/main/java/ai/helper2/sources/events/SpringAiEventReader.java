package ai.helper2.sources.events;

import ai.helper2.platform.llm.BudgetedChat;
import ai.helper2.platform.llm.CallOptions;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.llm.ModelAnswers;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class SpringAiEventReader implements EventReader {

  static final String PROMPT = "sources/read-event.txt";
  static final int MAX_PAGE_CHARS = 12_000;
  private static final int MAX_TITLE = 200;
  private static final String STEP = "read-event";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static String systemPrompt() {
    return PromptTemplate.fromClasspath(PROMPT)
        .render(Map.of("kinds", names(EventKind.values()), "formats", names(EventFormat.values())));
  }

  private final ChatClient chat;
  private final String system;
  private final String version;
  private final String model;
  private final int maxTokens;
  private final BudgetedChat calls;

  public SpringAiEventReader(
      ChatModel chatModel,
      String model,
      int maxTokens,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience) {
    this.chat = ChatClient.builder(chatModel).build();
    this.system = systemPrompt();
    this.version = ModelCallMetrics.promptVersion(system);
    this.model = model;
    this.maxTokens = maxTokens;
    this.calls = new BudgetedChat(LlmPolicies.CHAT, budget, metrics, resilience);
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public String promptVersion() {
    return version;
  }

  @Override
  public Optional<EventReading> read(String title, String pageText) {
    String user = userText(title, pageText);
    String answer;
    try {
      answer =
          calls.text(
              new BudgetedChat.Call(STEP, model, version),
              () -> new LlmUnavailable("daily event reader budget used up"),
              () ->
                  chat.prompt()
                      .options(CallOptions.of(model, maxTokens))
                      .system(system)
                      .user(user)
                      .call()
                      .chatResponse());
    } catch (CallRefused e) {
      throw new LlmUnavailable("model call refused: " + e.policy() + " circuit is " + e.reason());
    }
    return parse(answer);
  }

  static String userText(String title, String pageText) {
    String flatTitle = title == null ? "" : title.replaceAll("[\\r\\n]+", " ").strip();
    if (flatTitle.length() > MAX_TITLE) {
      flatTitle = flatTitle.substring(0, MAX_TITLE);
    }
    String page = pageText.replaceAll("(?i)</?page>", " ");
    if (page.length() > MAX_PAGE_CHARS) {
      page = page.substring(0, MAX_PAGE_CHARS);
    }
    return "Title: " + flatTitle + "\n<page>\n" + page + "\n</page>";
  }

  static Optional<EventReading> parse(String answer) {
    Optional<String> json = ModelAnswers.jsonObject(answer);
    if (json.isEmpty()) {
      return Optional.empty();
    }
    JsonNode root;
    try {
      root = JSON.readTree(json.get());
    } catch (JacksonException e) {
      return Optional.empty();
    }
    JsonNode events = root.path("events");
    if (!events.isInt()) {
      return Optional.empty();
    }
    return Optional.of(
        new EventReading(
            events.asInt(),
            day(root, "start"),
            day(root, "end"),
            text(root, "dateQuote"),
            value(EventKind.class, text(root, "kind")),
            text(root, "kindQuote"),
            value(EventFormat.class, text(root, "format")),
            text(root, "formatQuote"),
            text(root, "location"),
            text(root, "locationQuote")));
  }

  private static String text(JsonNode root, String field) {
    JsonNode n = root.path(field);
    return n.isString() && !n.asString().isBlank() ? n.asString() : null;
  }

  private static LocalDate day(JsonNode root, String field) {
    String t = text(root, field);
    if (t == null) {
      return null;
    }
    try {
      return LocalDate.parse(t.strip());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static <E extends Enum<E>> E value(Class<E> type, String name) {
    if (name == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, name.strip());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
  }
}
