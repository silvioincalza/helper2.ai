package ai.helper2.guide.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The AI guide's settings, from {@code helper2.guide}. The defaults and the {@code H2_} variables
 * are in application.yaml (ADR 0007 point 8).
 *
 * @param dailyModelCalls global model-call cap per UTC day (ADR 0017); 0 disables the model
 * @param clientDailyQuestions fresh questions per client per UTC day
 * @param clientDailyFreeAnswers answers with no AI pick given back to a client's daily questions
 *     per UTC day; past it such an answer counts like any other
 * @param questionDeadline a question is cancelled after this long
 * @param heartbeat how often the streamed guide sends a keep-alive comment
 * @param cacheTtl how long answers stay in the shared cache
 * @param similarThreshold reuse a similar question's answer at this cosine or closer (ADR 0041);
 *     null (empty) = off
 * @param maxCandidates how many results the reasoning model chooses from (ADR 0015)
 * @param chooseMaxTokens output cap of the choose call
 * @param thinking the model's thinking per call (ADR 0014)
 */
@Validated
@ConfigurationProperties("helper2.guide")
public record GuideProperties(
    @PositiveOrZero int dailyModelCalls,
    @PositiveOrZero int clientDailyQuestions,
    @PositiveOrZero int clientDailyFreeAnswers,
    @NotNull Duration questionDeadline,
    @NotNull Duration heartbeat,
    @NotNull Duration cacheTtl,
    @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") Double similarThreshold,
    @Positive int maxCandidates,
    @Positive int chooseMaxTokens,
    @Valid @NotNull Thinking thinking) {

  /** Thinking on or off for the plan, choose and verify calls. */
  public record Thinking(boolean plan, boolean choose, boolean verify) {}
}
