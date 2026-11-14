package ai.helper2.identity.schedule;

import ai.helper2.identity.AuditLog;
import ai.helper2.identity.config.IdentityRetentionProperties;
import ai.helper2.identity.usecase.PurgeExpiredAuditEntries;
import ai.helper2.platform.DailyJob;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AuditRetentionSchedule {

  @Bean
  PurgeExpiredAuditEntries purgeExpiredAuditEntries(
      AuditLog audit, Clock clock, IdentityRetentionProperties periods) {
    return new PurgeExpiredAuditEntries(audit, clock, periods.adminAudit());
  }

  @Bean
  DailyJob auditRetention(PurgeExpiredAuditEntries purge) {
    return new DailyJob("audit", purge::purge);
  }
}
