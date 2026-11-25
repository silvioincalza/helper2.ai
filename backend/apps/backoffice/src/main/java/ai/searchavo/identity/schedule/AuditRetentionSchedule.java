package ai.searchavo.identity.schedule;

import ai.searchavo.identity.AuditLog;
import ai.searchavo.identity.config.IdentityRetentionProperties;
import ai.searchavo.identity.usecase.PurgeExpiredAuditEntries;
import ai.searchavo.platform.DailyJob;
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
