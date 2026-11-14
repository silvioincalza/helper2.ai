package ai.helper2.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.runtime-settings")
public record RuntimeSettingsProperties(@NotNull Duration refresh) {

  @AssertTrue(message = "must be from PT5S to PT60S")
  public boolean isRefreshInRange() {
    return refresh == null
        || (refresh.compareTo(Duration.ofSeconds(5)) >= 0
            && refresh.compareTo(Duration.ofSeconds(60)) <= 0);
  }
}
