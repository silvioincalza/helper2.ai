package ai.helper2.research.config;

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
    @NotNull Model model,
    @Positive int maxTokens,
    @PositiveOrZero int dailyModelCalls,
    @PositiveOrZero int dailyPageReads,
    @PositiveOrZero int concurrency,
    @NotNull Duration tick) {

  public enum Model {
    FAST,
    REASONING
  }
}
