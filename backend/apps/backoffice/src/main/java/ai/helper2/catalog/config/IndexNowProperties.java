package ai.helper2.catalog.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.indexnow")
public record IndexNowProperties(
    @Pattern(regexp = "|[A-Za-z0-9-]{8,128}") String key,
    @NotBlank @Pattern(regexp = "https?://[^/]+") String publicBaseUrl,
    @NotBlank String cron,
    @NotNull URI endpoint,
    @NotNull Duration window,
    @NotNull Duration timeout,
    @Min(1) @Max(10_000) int batch) {

  public boolean on() {
    return !key.isEmpty();
  }
}
