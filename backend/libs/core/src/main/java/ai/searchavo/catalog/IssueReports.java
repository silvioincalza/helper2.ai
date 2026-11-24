package ai.searchavo.catalog;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface IssueReports {
  Optional<UUID> report(
      UUID resourceId,
      String kind,
      @Nullable String field,
      String message,
      String reason,
      String note);
}
