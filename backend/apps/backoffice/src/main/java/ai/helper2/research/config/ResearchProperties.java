package ai.helper2.research.config;

import ai.helper2.platform.llm.ModelTier;
import ai.helper2.research.usecase.RunResearch;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.research")
public record ResearchProperties(
    boolean enabled,
    @NotNull ModelTier model,
    @Positive int maxTokens,
    @PositiveOrZero int dailyModelCalls,
    @PositiveOrZero int dailyPageReads,
    @PositiveOrZero int concurrency,
    @Positive int maxStreams,
    @NotNull Duration checkUrlDeadline,
    @NotNull Duration tick) {

  @AssertTrue(
      message =
          "H2_RESEARCH_DAILY_MODEL_CALLS must be 0 or at least "
              + RunResearch.MAX_MODEL_CALLS
              + ", the model calls one run can take")
  public boolean isDailyModelCallsEnoughForOneRun() {
    return dailyModelCalls == 0 || dailyModelCalls >= RunResearch.MAX_MODEL_CALLS;
  }
}
