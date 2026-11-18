package ai.helper2.admin.usecase;

import ai.helper2.catalog.AiCheckReport;
import ai.helper2.catalog.AiCheckReport.Cursor;
import ai.helper2.catalog.AiCheckReport.Row;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.search.AiTopicCheck;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class CheckAiWords {

  public static final int MAX_PAGE = 2000;

  public record Flagged(Row row, boolean waiting) {}

  public record Page(List<Flagged> notAboutAi, int checked, @Nullable Cursor next) {}

  private final AiCheckReport report;
  private final AiTopicCheck aboutAi;
  private final EnrichmentBacklog backlog;

  public CheckAiWords(AiCheckReport report, AiTopicCheck aboutAi, EnrichmentBacklog backlog) {
    this.report = report;
    this.aboutAi = aboutAi;
    this.backlog = backlog;
  }

  public Page next(@Nullable Cursor after, int size) {
    int limit = Math.min(size, MAX_PAGE);
    List<Row> rows = report.mostPopular(after, limit);
    List<Row> failing = failingTheWordRule(rows);
    return new Page(flagWaiting(failing), rows.size(), nextCursor(rows, limit));
  }

  private List<Row> failingTheWordRule(List<Row> rows) {
    return rows.stream()
        .filter(r -> !aboutAi.isAboutAi(r.title(), r.description(), r.tags()))
        .toList();
  }

  private List<Flagged> flagWaiting(List<Row> failing) {
    Set<UUID> waiting =
        new HashSet<>(backlog.waiting(failing.stream().map(Row::resourceId).toList()));
    return failing.stream().map(r -> new Flagged(r, waiting.contains(r.resourceId()))).toList();
  }

  private static @Nullable Cursor nextCursor(List<Row> rows, int limit) {
    if (rows.isEmpty() || rows.size() < limit) {
      return null;
    }
    Row last = rows.getLast();
    return new Cursor(last.popularity(), last.resourceId());
  }
}
