package ai.helper2.sources.classifier;

import ai.helper2.platform.llm.BudgetedChat;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.llm.TextCall;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.scout.UntrustedPageText;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * UC-030 BR-013: asks the fast model whether a page is the page of a named item. The model gets a
 * fixed instruction, the name and a cut of the cleaned page text; the page is untrusted, so only
 * the one word "yes" or "no" is taken from the answer and everything else is no answer.
 */
public final class SpringAiItemPageJudge implements ItemPageJudge {

  static final int MAX_NAME = 200;

  static final int MAX_PAGE_TEXT = 2_000;

  private static final String STEP = "check-item-page";

  private static final Logger log = LoggerFactory.getLogger(SpringAiItemPageJudge.class);

  private final TextCall call;

  public SpringAiItemPageJudge(
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

  static String systemPrompt() {
    return PromptTemplate.fromClasspath("sources/check-item-page.txt").render();
  }

  @Override
  public Answer isPageOf(String name, String pageText) {
    try {
      return parse(call.answer(user(name, pageText), "daily budget used up", LlmUnavailable::new));
    } catch (LlmUnavailable e) {
      log.info("the check of a page of an item gave no answer: {}", e.getMessage());
      return Answer.NONE;
    }
  }

  static String user(String name, String pageText) {
    String page =
        cut(UntrustedPageText.INSTANCE.clean(pageText).replace("</page>", ""), MAX_PAGE_TEXT);
    return "Name: "
        + cut(name.replaceAll("[\\s<>]+", " ").strip(), MAX_NAME)
        + "\n<page>\n"
        + page
        + "\n</page>";
  }

  /** Only the one word counts: a sentence, a reason or anything else is no answer. */
  static Answer parse(@Nullable String answer) {
    String word = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
    return switch (word) {
      case "yes" -> Answer.YES;
      case "no" -> Answer.NO;
      default -> Answer.NONE;
    };
  }

  private static String cut(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }
}
