package ai.helper2.admin.usecase;

import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogCursor;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogEntry;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.View;
import ai.helper2.platform.BadRequestException;
import ai.helper2.scout.EnrichmentBacklog;
import java.util.List;
import org.jspecify.annotations.Nullable;

public class ListEnrichmentQueue {

  public static final int MAX_PAGE_SIZE = 100;

  public record Page(List<BacklogEntry> items, @Nullable BacklogCursor next) {}

  private final EnrichmentBacklog backlog;

  public ListEnrichmentQueue(EnrichmentBacklog backlog) {
    this.backlog = backlog;
  }

  public Page list(View view, @Nullable BacklogCursor after, int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
    }
    List<BacklogEntry> read = backlog.queue(view, after, size + 1);
    if (read.size() <= size) {
      return new Page(read, null);
    }
    List<BacklogEntry> items = read.subList(0, size);
    return new Page(items, items.getLast().cursor());
  }
}
