package ai.searchavo.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.retention")
public record RetentionSchedule(@NotBlank String cron) {

  @AssertTrue(message = "must be a valid cron expression")
  public boolean isCronValid() {
    return cron == null || CronExpression.isValidExpression(cron);
  }
}
