package ai.helper2.identity.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.identity")
public record IdentityProperties(
    @NotBlank String frontendUrl,
    @NotNull List<String> admins,
    boolean metricsPublic,
    boolean noindex) {

  public IdentityProperties {
    admins =
        admins == null
            ? List.of()
            : admins.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
  }
}
