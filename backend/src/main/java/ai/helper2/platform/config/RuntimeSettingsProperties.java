package ai.helper2.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The runtime settings (ADR 0060), from {@code helper2.runtime-settings}.
 *
 * @param refresh how often each replica checks the store for a change; 5 s to 60 s
 */
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
