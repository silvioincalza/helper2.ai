package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.helper2.guide.CallTimeLimit;
import ai.helper2.guide.GuideBudgetExhausted;
import ai.helper2.guide.config.ModelStream;
import ai.helper2.guide.internal.FakeProvider.Reply;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

@ParameterizedClass
@EnumSource(ModelStream.class)
class ThinkingOnTheWireTest {

  @Parameter ModelStream stream;

  final FakeProvider provider = new FakeProvider();

  @AfterEach
  void stop() {
    provider.close();
  }

  private SpringAiGuideModel model(SpringAiGuideModel.Thinking thinking) {
    return model(thinking, new DailyBudget(10, Clock.systemUTC(), "guide"));
  }

  private SpringAiGuideModel model(SpringAiGuideModel.Thinking thinking, DailyBudget budget) {
    return new SpringAiGuideModel(
        provider.chatModel(),
        "test",
        "fast",
        "reasoning",
        true,
        8192,
        thinking,
        budget,
        ModelCallMetrics.none(),
        Resilience.NONE,
        CallTimeLimit.NONE,
        Clock.systemUTC(),
        stream);
  }

  @Test
  void thinkingOffIsInTheRequestBody() {
    model(SpringAiGuideModel.Thinking.DEFAULT).plan("postgres MCP", null);

    assertThat(provider.bodies)
        .singleElement()
        .satisfies(body -> {
          assertThat(body)
              .contains("\"chat_template_kwargs\"")
              .contains("\"enable_thinking\":false");
          assertThat(body).contains("\"model\":\"fast\"");
        });
  }

  @Test
  void aThinkingBudgetIsInTheRequestBodyOnTopOfTheRoomForTheAnswer() {
    model(new SpringAiGuideModel.Thinking(512, 0, 0, 0)).plan("postgres MCP", null);

    assertThat(provider.bodies)
        .singleElement()
        .satisfies(body ->
            assertThat(body)
                .contains("\"enable_thinking\":true")
                .contains("\"thinking_token_budget\":512")
                .contains("\"max_tokens\":" + (SpringAiGuideModel.PLAN_MAX_TOKENS + 512)));
  }

  @Test
  void planAsksForATokenLimitWithThinkingOff() {
    model(SpringAiGuideModel.Thinking.DEFAULT).plan("postgres MCP", null);

    assertThat(provider.bodies)
        .singleElement()
        .satisfies(body ->
            assertThat(body).contains("\"max_tokens\":" + SpringAiGuideModel.PLAN_MAX_TOKENS));
  }

  @Test
  void aThinkingAnswerCutAtTheLimitIsAskedAgainWithoutThinking() {
    provider.then(Reply.cut("{\"searchQuery\":\"MCP ser"));

    var plan = model(new SpringAiGuideModel.Thinking(512, 0, 0, 0)).plan("postgres MCP", null);

    assertThat(plan.getSearchQuery()).isEqualTo("MCP server postgres");
    assertThat(provider.bodies).hasSize(2);
    assertThat(provider.bodies.get(0)).contains("\"thinking_token_budget\":512");
    assertThat(provider.bodies.get(1))
        .contains("\"enable_thinking\":false")
        .doesNotContain("thinking_token_budget");
  }

  @Test
  void aSpentBudgetRefusesTheCallBeforeAnyRequestAndSaysWhenItOpensAgain() {
    DailyBudget spent = new DailyBudget(0, Clock.systemUTC(), "guide");

    Throwable failure =
        catchThrowable(
            () -> model(SpringAiGuideModel.Thinking.DEFAULT, spent).plan("postgres MCP", null));

    assertThat(failure)
        .isInstanceOfSatisfying(
            GuideBudgetExhausted.class,
            refused -> {
              assertThat(refused.getCategory()).isEqualTo("budget");
              assertThat(refused.getOpensMoreToday()).isEqualTo(spent.opensMoreToday());
              assertThat(refused.getOpensAgainAt()).isEqualTo(spent.opensAgainAt());
            });
    assertThat(provider.bodies).isEmpty();
  }
}
