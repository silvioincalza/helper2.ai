package ai.helper2.catalog.usecase;

import ai.helper2.catalog.IssueReports;
import java.util.Optional;
import java.util.UUID;

public class ReportIssue {

  public enum Kind {
    WRONG,
    OUTDATED,
    BROKEN_LINK,
    OTHER
  }

  private final IssueReports reports;

  public ReportIssue(IssueReports reports) {
    this.reports = reports;
  }

  public Optional<UUID> submit(UUID resourceId, Kind kind, String field, String message) {
    return reports.report(
        resourceId,
        kind.name(),
        field,
        message,
        "User report: " + kind.name().toLowerCase(),
        (field == null ? "" : "[" + field + "] ") + message);
  }
}
