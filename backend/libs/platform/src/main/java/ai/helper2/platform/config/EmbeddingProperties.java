package ai.helper2.platform.config;

import ai.helper2.shared.Secrets;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.embedding")
public record EmbeddingProperties(
    @NotNull URI baseUrl,
    @NotNull String apiKey,
    @NotBlank String model,
    @Positive int dimensions,
    @NotNull Duration timeout) {

  public boolean enabled() {
    return !apiKey.isBlank();
  }

  @Override
  public String toString() {
    return "EmbeddingProperties[baseUrl="
        + baseUrl
        + ", apiKey="
        + Secrets.mask(apiKey)
        + ", model="
        + model
        + ", dimensions="
        + dimensions
        + ", timeout="
        + timeout
        + "]";
  }
}
