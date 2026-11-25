package ai.searchavo.sources.classifier;

import ai.searchavo.platform.llm.BudgetedChat;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.LlmPolicies;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.llm.TextCall;
import ai.searchavo.platform.prompt.PromptTemplate;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.LlmUnavailable;
import ai.searchavo.scout.UntrustedPageText;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
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
    return parse(call.answer(user(name, pageText), "daily budget used up", LlmUnavailable::new));
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
