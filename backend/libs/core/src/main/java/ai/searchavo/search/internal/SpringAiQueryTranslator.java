package ai.searchavo.search.internal;

import ai.searchavo.platform.cache.SharedCache;
import ai.searchavo.platform.llm.BudgetedChat;
import ai.searchavo.platform.llm.CallOptions;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.LlmPolicies;
import ai.searchavo.platform.llm.ModelAnswers;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.llm.UntrustedText;
import ai.searchavo.platform.prompt.PromptTemplate;
import ai.searchavo.platform.resilience.CallRefused;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.search.QueryTranslator;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

class SpringAiQueryTranslator implements QueryTranslator {

  private static final Logger log = LoggerFactory.getLogger(SpringAiQueryTranslator.class);

  static final int MAX_QUERY_CHARS = 500;

  private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2,3}");

  static String systemPrompt() {
    return PromptTemplate.fromClasspath("search/translate-query.txt")
        .render(Map.of("maxWords", QueryTranslator.MAX_WORDS));
  }

  private final ChatClient chat;
  private final String system;
  private final String version;
  private final String cacheVersion;
  private final String model;
  private final SharedCache cache;
  private final Duration ttl;
  private final BudgetedChat calls;

  private static final class BudgetSpent extends RuntimeException {
    BudgetSpent() {
      super("daily translation budget used up", null, false, false);
    }
  }

  SpringAiQueryTranslator(
      ChatModel chatModel,
      String model,
      DailyBudget budget,
      SharedCache cache,
      Duration ttl,
      ModelCallMetrics metrics,
      Resilience resilience) {
    this.calls = new BudgetedChat(LlmPolicies.CHAT, budget, metrics, resilience);
    this.chat = ChatClient.builder(chatModel).build();
    this.system = systemPrompt();
    this.version = ModelCallMetrics.promptVersion(system);
    this.cacheVersion = cacheVersion(system);
    this.model = model;
    this.cache = cache;
    this.ttl = ttl;
  }

  // UC-001 BR-010: within the daily translations, each reused for its time to live.
  @Override
  public String toEnglish(String text, String language) {
    if (language == null || !LANGUAGE_CODE.matcher(language).matches()) {
      return null;
    }
    String key = cache.key(model, cacheVersion, language, SharedCache.normalisedQuery(text));
    String cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    String english;
    try {
      english =
          calls.text(
              new BudgetedChat.Call("translate", model, version),
              BudgetSpent::new,
              () ->
                  chat.prompt()
                      .options(CallOptions.of(model, 400))
                      .system(system)
                      .user(userMessage(text, language))
                      .call()
                      .chatResponse());
    } catch (BudgetSpent e) {
      log.info("Query translation skipped: daily translation budget used up");
      return null;
    } catch (CallRefused e) {
      log.info("Query translation skipped: {} is {}", e.policy(), e.reason());
      return null;
    } catch (RuntimeException e) {
      log.info("Query translation failed: {}", e.getClass().getSimpleName());
      return null;
    }
    english = ModelAnswers.withoutReasoning(english);
    if (english.isEmpty()) {
      return null;
    }
    english = QueryTranslator.bounded(english.lines().reduce((first, second) -> second).orElse(""));
    if (english.isEmpty()) {
      return null;
    }
    cache.put(key, english, ttl);
    return english;
  }

  static String cacheVersion(String system) {
    return ModelCallMetrics.promptVersion(system + "\u0000" + userMessage("{query}", "{language}"));
  }

  static String userMessage(String text, String language) {
    return "Language: "
        + language
        + "\n<query>\n"
        + UntrustedText.untrusted(text, MAX_QUERY_CHARS)
        + "\n</query>";
  }
}
