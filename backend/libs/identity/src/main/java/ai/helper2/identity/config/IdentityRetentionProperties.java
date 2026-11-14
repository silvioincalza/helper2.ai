package ai.helper2.identity.config;

import ai.helper2.identity.AuditLog;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.retention")
public record IdentityRetentionProperties(
    @NotNull Duration inactiveAccounts, @NotNull Duration adminAudit) {

  @AssertTrue(
      message =
          "helper2.retention.admin-audit must be at least P730D (V1 keeps every entry that long)")
  boolean isAdminAuditAtLeastWhatTheTableKeeps() {
    return adminAudit == null || adminAudit.compareTo(AuditLog.SHORTEST_RETENTION) >= 0;
  }
}
