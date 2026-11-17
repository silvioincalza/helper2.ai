package ai.helper2.admin.usecase;

import ai.helper2.catalog.AiCheckReport;
import ai.helper2.catalog.AiCheckReport.Cursor;
import ai.helper2.catalog.AiCheckReport.Row;
import ai.helper2.search.AiTopicCheck;
import java.util.List;
import org.jspecify.annotations.Nullable;

public class CheckAiWords {

  public static final int MAX_PAGE = 2000;

  public record Page(List<Row> notAboutAi, int checked, @Nullable Cursor next) {}

  private final AiCheckReport report;
  private final AiTopicCheck aboutAi;

  public CheckAiWords(AiCheckReport report, AiTopicCheck aboutAi) {
    this.report = report;
    this.aboutAi = aboutAi;
  }

  public Page next(@Nullable Cursor after, int size) {
    List<Row> rows = report.mostPopular(after, Math.min(size, MAX_PAGE));
    Row last = rows.isEmpty() ? null : rows.getLast();
    return new Page(
        rows.stream()
            .filter(r -> !aboutAi.isAboutAi(r.title(), r.description(), r.tags()))
            .toList(),
        rows.size(),
        last == null || rows.size() < Math.min(size, MAX_PAGE)
            ? null
            : new Cursor(last.popularity(), last.resourceId()));
  }
}
