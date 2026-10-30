package ai.helper2.identity.usecase;

import ai.helper2.identity.internal.AuditLog;
import ai.helper2.identity.internal.Users;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Identity's retention rules (ADR 0034): accounts nobody signed in to for [inactiveAccounts] go with their bookmarks
 * (issue reports stay, without a reporter); admin audit entries older than [adminAudit] are deleted.
 */
@Service("identityPurgeExpiredData")
public class PurgeExpiredData {

    private static final Logger log = LoggerFactory.getLogger(PurgeExpiredData.class);

    /** What one purge deleted. */
    public record Purged(int accounts, int auditEntries) {
    }

    private final Users users;
    private final AuditLog audit;
    private final Clock clock;
    private final Duration inactiveAccounts;
    private final Duration adminAudit;

    public PurgeExpiredData(Users users, AuditLog audit, Clock clock,
                            @Value("${helper2.retention.inactive-accounts}") Duration inactiveAccounts,
                            @Value("${helper2.retention.admin-audit}") Duration adminAudit) {
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.inactiveAccounts = inactiveAccounts;
        this.adminAudit = adminAudit;
    }

    public Purged purge() {
        Instant now = clock.instant();
        Purged purged = new Purged(users.deleteSignedInLastBefore(now.minus(inactiveAccounts)),
            audit.deleteBefore(now.minus(adminAudit)));
        log.info("identity retention: {}", purged);
        return purged;
    }
}
