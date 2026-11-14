package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CatalogWriter;
import java.util.Optional;
import java.util.UUID;

public class ReportIssue {

  public enum Kind {
    WRONG,
    OUTDATED,
    BROKEN_LINK,
    OTHER
  }

  private final CatalogWriter writer;

  public ReportIssue(CatalogWriter writer) {
    this.writer = writer;
  }

  public Optional<UUID> submit(UUID resourceId, Kind kind, String field, String message) {
    return writer.report(
        resourceId,
        kind.name(),
        field,
        message,
        "User report: " + kind.name().toLowerCase(),
        (field == null ? "" : "[" + field + "] ") + message);
  }
}
