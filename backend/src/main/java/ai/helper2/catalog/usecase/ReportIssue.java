package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.internal.IssueReports;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A user reports "this is wrong or outdated". Never change data directly; they open a review item. */
@Service
public class ReportIssue {

    public enum Kind { WRONG, OUTDATED, BROKEN_LINK, OTHER }

    private final IssueReports reports;
    private final ReviewQueue reviewQueue;

    public ReportIssue(IssueReports reports, ReviewQueue reviewQueue) {
        this.reports = reports;
        this.reviewQueue = reviewQueue;
    }

    /** Returns empty when the resource does not exist. */
    @Transactional
    public Optional<UUID> submit(UUID resourceId, Kind kind, String field, String message, UUID reporterUserId) {
        Optional<UUID> report = reports.add(resourceId, kind.name(), field, message, reporterUserId);
        report.ifPresent(id -> reviewQueue.open(ReviewQueue.Kind.USER_REPORT, resourceId, "User report: " + kind.name().toLowerCase(),
            (field == null ? "" : "[" + field + "] ") + message, new ReviewQueue.Links(null, null, id)));
        return report;
    }
}
