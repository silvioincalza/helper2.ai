package ai.helper2.guide.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.guide")
public record GuideProperties(
    @PositiveOrZero int dailyModelCalls,
    @PositiveOrZero int clientDailyQuestions,
    @PositiveOrZero int clientDailyFreeAnswers,
    @NotNull Duration questionDeadline,
    @NotNull Duration modelCallTimeout,
    @NotNull Duration modelCallMinimum,
    @NotNull Duration deadlineMargin,
    @NotNull Duration heartbeat,
    @NotNull Duration cacheTtl,
    @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") Double similarThreshold,
    @Positive int maxCandidates,
    @Positive int chooseMaxTokens,
    @NotNull ModelStream modelStream,
    @Valid @NotNull Thinking thinking,
    @Valid @NotNull Fairness fairness) {
  public record Thinking(
      @PositiveOrZero int planTokens,
      @PositiveOrZero int chooseTokens,
      @PositiveOrZero int verifyTokens,
      @PositiveOrZero int browseTokens) {

    public boolean inAGuideStep() {
      return planTokens > 0 || chooseTokens > 0 || verifyTokens > 0;
    }
  }

  public record Fairness(
      @PositiveOrZero int networkDailyQuestions,
      @Positive int clientConcurrentQuestions,
      @Min(1) @Max(24) int budgetPaceSlots) {}

  public boolean stopsAQuestionNobodyWaitsFor() {
    return modelStream == ModelStream.ON && thinking.inAGuideStep();
  }

  @AssertTrue(
      message = "the question deadline must leave room for two model calls (plan and choose)")
  public boolean isDeadlineLongerThanTwoModelCalls() {
    return questionDeadline == null
        || modelCallTimeout == null
        || questionDeadline.compareTo(modelCallTimeout.multipliedBy(2)) > 0;
  }

  @AssertTrue(
      message =
          "the shortest model call and the deadline margin must fit in one model call timeout")
  public boolean isShortestCallInsideOneModelCall() {
    return modelCallTimeout == null
        || modelCallMinimum == null
        || deadlineMargin == null
        || (!deadlineMargin.isNegative()
            && modelCallMinimum.isPositive()
            && modelCallTimeout.compareTo(modelCallMinimum.plus(deadlineMargin)) >= 0);
  }
}
