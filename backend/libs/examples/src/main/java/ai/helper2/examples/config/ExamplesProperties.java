package ai.helper2.examples.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * When the quality check of the home examples runs (ADR 0015), bound from {@code helper2.examples}
 * (defaults and H2_ names in application.yaml). The check schedule reads these keys as
 * placeholders.
 *
 * @param pendingDelay after startup, before the first look at PENDING examples
 * @param pendingEvery between two looks at PENDING examples
 * @param cron when every example is checked again (UTC)
 * @param draftCalls model calls a day for drafting examples (one per draft run, a second when its
 *     answer cannot be read); 0 turns drafts off
 * @param draftSources catalog resources given to the model in one draft run
 */
@Validated
@ConfigurationProperties("helper2.examples")
public record ExamplesProperties(
    @NotNull Duration pendingDelay,
    @NotNull Duration pendingEvery,
    @NotBlank String cron,
    @PositiveOrZero int draftCalls,
    @Min(1) @Max(30) int draftSources) {}
