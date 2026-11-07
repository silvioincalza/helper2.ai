package ai.helper2.platform.config;

import ai.helper2.shared.Secrets;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The HMAC key of the daily quota counters (ADR 0037), from {@code helper2.quota}. It may be empty
 * here: the search module stops startup when a metered source is enabled without it.
 */
@Validated
@ConfigurationProperties("helper2.quota")
public record QuotaProperties(@NotNull String keySecret) {

  @Override
  public String toString() {
    return "QuotaProperties[keySecret=" + Secrets.mask(keySecret) + "]";
  }
}
