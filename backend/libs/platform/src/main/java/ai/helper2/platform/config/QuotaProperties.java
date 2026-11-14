package ai.helper2.platform.config;

import ai.helper2.shared.Secrets;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.quota")
public record QuotaProperties(@NotNull String keySecret) {

  @Override
  public String toString() {
    return "QuotaProperties[keySecret=" + Secrets.mask(keySecret) + "]";
  }
}
