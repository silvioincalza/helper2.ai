package ai.helper2.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.http")
public record HttpClientProperties(
    @NotNull Duration poolWait,
    @Positive int maxConnections,
    @Positive int maxConnectionsPerRoute,
    @NotNull DataSize maxResponseSize) {

  @AssertTrue(message = "pool-wait must be longer than zero")
  public boolean isPoolWaitPositive() {
    return poolWait == null || poolWait.isPositive();
  }

  @AssertTrue(message = "max-connections-per-route must not be above max-connections")
  public boolean isPerRouteInsideThePool() {
    return maxConnectionsPerRoute <= maxConnections;
  }

  @AssertTrue(message = "max-response-size must be at least one byte")
  public boolean isResponseSizePositive() {
    return maxResponseSize == null || maxResponseSize.toBytes() > 0;
  }
}
