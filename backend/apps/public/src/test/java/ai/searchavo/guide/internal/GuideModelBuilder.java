package ai.searchavo.guide.internal;

import ai.searchavo.guide.CallTimeLimit;
import ai.searchavo.guide.config.ModelStream;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.resilience.Resilience;
import java.time.Clock;
import org.springframework.ai.chat.model.ChatModel;

final class GuideModelBuilder {

  private final ChatModel chat;
  private String provider = "fake";
  private String fastModel = "fast";
  private String reasoningModel = "reasoning";
  private int chooseMaxTokens = SpringAiGuideModel.DEFAULT_CHOOSE_MAX_TOKENS;
  private SpringAiGuideModel.Thinking thinking = SpringAiGuideModel.Thinking.DEFAULT;
  private DailyBudget budget = new DailyBudget(Integer.MAX_VALUE, Clock.systemUTC(), "guide");
  private ModelCallMetrics metrics = ModelCallMetrics.none();
  private Resilience resilience = Resilience.NONE;
  private CallTimeLimit timeLimit = CallTimeLimit.NONE;
  private Clock clock = Clock.systemUTC();

  private GuideModelBuilder(ChatModel chat) {
    this.chat = chat;
  }

  static GuideModelBuilder over(ChatModel chat) {
    return new GuideModelBuilder(chat);
  }

  GuideModelBuilder named(String provider, String fastModel, String reasoningModel) {
    this.provider = provider;
    this.fastModel = fastModel;
    this.reasoningModel = reasoningModel;
    return this;
  }

  GuideModelBuilder chooseMaxTokens(int chooseMaxTokens) {
    this.chooseMaxTokens = chooseMaxTokens;
    return this;
  }

  GuideModelBuilder thinking(SpringAiGuideModel.Thinking thinking) {
    this.thinking = thinking;
    return this;
  }

  GuideModelBuilder budget(DailyBudget budget) {
    this.budget = budget;
    return this;
  }

  GuideModelBuilder metrics(ModelCallMetrics metrics) {
    this.metrics = metrics;
    return this;
  }

  GuideModelBuilder resilience(Resilience resilience) {
    this.resilience = resilience;
    return this;
  }

  GuideModelBuilder timeLimit(CallTimeLimit timeLimit) {
    this.timeLimit = timeLimit;
    return this;
  }

  GuideModelBuilder clock(Clock clock) {
    this.clock = clock;
    return this;
  }

  SpringAiGuideModel build() {
    return new SpringAiGuideModel(
        chat,
        provider,
        fastModel,
        reasoningModel,
        true,
        chooseMaxTokens,
        thinking,
        budget,
        metrics,
        resilience,
        timeLimit,
        clock,
        ModelStream.OFF);
  }
}
