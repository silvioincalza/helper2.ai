package ai.helper2.identity.usecase;

import ai.helper2.identity.internal.AuditLog;
import ai.helper2.platform.BadRequestException;
import org.springframework.stereotype.Service;

/** One page of the admin audit trail, newest first. */
@Service
public class ListAuditEntries {

    /** The largest page the back office may ask for. */
    public static final int MAX_PAGE_SIZE = 100;

    private final AuditLog log;

    public ListAuditEntries(AuditLog log) {
        this.log = log;
    }

    public AuditLog.Page list(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        }
        return log.page(page, size);
    }
}
