package ai.helper2.examples.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.examples")
public record ExamplesProperties(
    @NotNull Duration pendingDelay,
    @NotNull Duration pendingEvery,
    @NotBlank String cron,
    @PositiveOrZero int draftCalls,
    @Min(1) @Max(30) int draftSources) {}
