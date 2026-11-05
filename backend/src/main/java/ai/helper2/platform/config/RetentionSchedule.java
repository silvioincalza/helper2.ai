package ai.helper2.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

/**
 * When the daily purges run (ADR 0034), from {@code helper2.retention.cron}, UTC. The {@code
 * Scheduled} methods of each module read the same key; this record makes a bad expression stop
 * startup with its name. Each module binds its own retention periods under the same prefix.
 */
@Validated
@ConfigurationProperties("helper2.retention")
public record RetentionSchedule(@NotBlank String cron) {

  @AssertTrue(message = "must be a valid cron expression")
  public boolean isCronValid() {
    return cron == null || CronExpression.isValidExpression(cron);
  }
}
