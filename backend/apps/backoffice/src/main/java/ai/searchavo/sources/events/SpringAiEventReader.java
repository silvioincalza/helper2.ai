package ai.searchavo.sources.events;

import ai.searchavo.platform.llm.BudgetedChat;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.LlmPolicies;
import ai.searchavo.platform.llm.ModelAnswers;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.llm.TextCall;
import ai.searchavo.platform.llm.UntrustedText;
import ai.searchavo.platform.prompt.PromptTemplate;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.LlmUnavailable;
import ai.searchavo.shared.EventFormat;
import ai.searchavo.shared.EventKind;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
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

  private final TextCall call;

  public SpringAiEventReader(
      ChatModel chatModel,
      String model,
      int maxTokens,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience) {
    this.call =
        new TextCall(
            chatModel,
            new BudgetedChat(LlmPolicies.CHAT, budget, metrics, resilience),
            STEP,
            model,
            maxTokens,
            systemPrompt());
  }

  @Override
  public String model() {
    return call.model();
  }

  @Override
  public String promptVersion() {
    return call.promptVersion();
  }

  @Override
  public Optional<EventReading> read(String title, String pageText) {
    return parse(
        call.answer(
            userText(title, pageText), "daily event reader budget used up", LlmUnavailable::new));
  }

  static String userText(String title, String pageText) {
    String flatTitle =
        UntrustedText.untrusted(
            title == null ? "" : title.replaceAll("[\\r\\n]+", " ").strip(), MAX_TITLE);
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
    return EnumUtils.getEnum(type, StringUtils.strip(name));
  }

  private static String names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
  }
}
