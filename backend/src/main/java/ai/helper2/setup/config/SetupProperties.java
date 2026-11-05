package ai.helper2.setup.config;

import ai.helper2.shared.Secrets;
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

/**
 * The setup guide's settings, from {@code helper2.setup}. The defaults and the {@code H2_}
 * variables are in application.yaml (ADR 0007 point 8); the hosts and the registry repeat the
 * sources' keys there, so the setup guide reads the same hosts without reading the sources' prefix.
 *
 * @param documentTimeout per document fetch
 * @param npmReadmeBaseUrl where npm READMEs are read (jsDelivr)
 * @param cacheTtl how long registry entries and documents are kept in memory
 * @param documentCache the most document text kept in memory per replica; a plain number is
 *     megabytes
 * @param hourlyFetches documents fetched per UTC hour across replicas
 * @param githubToken the setup guide's own GitHub token, never the Scout's; empty reads anonymously
 * @param hosts the hosts the documents are read from
 * @param mcpRegistry the MCP Registry, read per request
 * @param guide the step-by-step guide's model settings
 */
@Validated
@ConfigurationProperties("helper2.setup")
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

  /** GitHub and Hugging Face, with the Hugging Face token of the sources. */
  public record Hosts(@NotNull URI github, @NotNull URI huggingface, @NotNull String hfToken) {

    /** The default record text would print the token. */
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

  /** The registry the Scout's collector reads too, with the same timeout. */
  public record McpRegistry(@NotNull URI baseUrl, @NotNull Duration timeout) {}

  /**
   * [dailyModelCalls] the guide's own cap; [cacheTtl] how long answers stay in the shared cache;
   * [unreadableTtl] how long the "unavailable" answer after two unreadable model answers stays
   * there.
   */
  public record Guide(
      @PositiveOrZero int dailyModelCalls,
      boolean reasoning,
      @NotNull Duration cacheTtl,
      @NotNull Duration unreadableTtl) {}

  /** The default record text would print the token. */
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
