package ai.helper2.identity.usecase;

import ai.helper2.identity.AuditLog;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PurgeExpiredAuditEntries {

  private static final Logger log = LoggerFactory.getLogger(PurgeExpiredAuditEntries.class);

  private final AuditLog audit;
  private final Clock clock;
  private final Duration adminAudit;

  public PurgeExpiredAuditEntries(AuditLog audit, Clock clock, Duration adminAudit) {
    if (adminAudit.compareTo(AuditLog.SHORTEST_RETENTION) < 0) {
      throw new IllegalArgumentException(
          "The admin audit retention must be at least "
              + AuditLog.SHORTEST_RETENTION.toDays()
              + " days, not "
              + adminAudit.toDays());
    }
    this.audit = audit;
    this.clock = clock;
    this.adminAudit = adminAudit;
  }

  public int purge() {
    int deleted = audit.deleteBefore(clock.instant().minus(adminAudit));
    log.info("audit retention: {} admin audit entries deleted", deleted);
    return deleted;
  }
}
