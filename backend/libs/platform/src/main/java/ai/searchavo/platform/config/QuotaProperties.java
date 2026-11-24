package ai.searchavo.platform.config;

import ai.searchavo.shared.Secrets;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.quota")
public record QuotaProperties(@NotNull String keySecret) {

  @Override
  public String toString() {
    return "QuotaProperties[keySecret=" + Secrets.mask(keySecret) + "]";
  }
}
