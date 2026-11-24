package ai.searchavo.setup.config;

import ai.searchavo.setup.StepTextMode;
import ai.searchavo.shared.Secrets;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.setup")
public record SetupProperties(
    @NotNull Duration documentTimeout,
    @NotNull URI npmReadmeBaseUrl,
    @NotNull Duration cacheTtl,
    @DataSizeUnit(DataUnit.MEGABYTES) @NotNull DataSize documentCache,
    @Positive int hourlyFetches,
    @NotNull String githubToken,
    @Valid @NotNull Hosts hosts,
    @Valid @NotNull McpRegistry mcpRegistry,
    @Valid @NotNull Guide guide) {
  public record Hosts(@NotNull URI github, @NotNull URI huggingface, @NotNull String hfToken) {
    @Override
    public String toString() {
      return "Hosts[github="
          + github
          + ", huggingface="
          + huggingface
          + ", hfToken="
          + Secrets.mask(hfToken)
          + "]";
    }
  }

  public record McpRegistry(@NotNull URI baseUrl, @NotNull Duration timeout) {}

  public record Guide(
      @PositiveOrZero int dailyModelCalls,
      @PositiveOrZero int clientDailyGuides,
      @PositiveOrZero int networkDailyGuides,
      boolean reasoning,
      @NotNull Duration cacheTtl,
      @NotNull Duration unreadableTtl,
      @NotNull StepTextMode stepText) {}

  @Override
  public String toString() {
    return "SetupProperties[documentTimeout="
        + documentTimeout
        + ", npmReadmeBaseUrl="
        + npmReadmeBaseUrl
        + ", cacheTtl="
        + cacheTtl
        + ", documentCache="
        + documentCache
        + ", hourlyFetches="
        + hourlyFetches
        + ", githubToken="
        + Secrets.mask(githubToken)
        + ", hosts="
        + hosts
        + ", mcpRegistry="
        + mcpRegistry
        + ", guide="
        + guide
        + "]";
  }
}
