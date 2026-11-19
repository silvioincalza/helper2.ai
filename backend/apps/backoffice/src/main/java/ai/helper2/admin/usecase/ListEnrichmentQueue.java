package ai.helper2.admin.usecase;

import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogCursor;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogEntry;
import ai.helper2.scout.EnrichmentBacklog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ListEnrichmentQueue {

  public enum Section {
    WAITING,
    SKIPPED,
    OUT_OF_RETRIES;

    EnrichmentCandidates.View inBacklog() {
      return EnrichmentCandidates.View.valueOf(name());
    }
  }

  public record Entry(
      UUID id,
      String title,
      EnrichmentCandidates.State state,
      EnrichmentCandidates.@Nullable Waiting waiting,
      Instant firstDiscoveredAt,
      int failedRuns,
      @Nullable String lastOutcome,
      @Nullable Instant lastRunAt,
      @Nullable Instant skippedAt,
      @Nullable String skipReason) {

    static Entry of(BacklogEntry e) {
      return new Entry(
          e.id(),
          e.title(),
          e.state(),
          e.waiting(),
          e.firstDiscoveredAt(),
          e.failedRuns(),
          e.lastOutcome(),
          e.lastRunAt(),
          e.skippedAt(),
          e.skipReason());
    }
  }

  public record Page(List<Entry> items, @Nullable BacklogCursor next) {}

  private final EnrichmentBacklog backlog;

  public ListEnrichmentQueue(EnrichmentBacklog backlog) {
    this.backlog = backlog;
  }

  public Page list(Section section, @Nullable BacklogCursor after, int size) {
    List<BacklogEntry> read = backlog.queue(section.inBacklog(), after, size + 1);
    if (read.size() <= size) {
      return new Page(entries(read), null);
    }
    List<BacklogEntry> shown = read.subList(0, size);
    return new Page(entries(shown), shown.getLast().cursor());
  }

  private static List<Entry> entries(List<BacklogEntry> read) {
    return read.stream().map(Entry::of).toList();
  }
}
