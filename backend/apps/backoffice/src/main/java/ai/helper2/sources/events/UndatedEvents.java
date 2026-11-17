package ai.helper2.sources.events;

import java.time.Instant;
import java.util.List;

/** Catalog events with no stated start day: their page is read again for its days. */
@FunctionalInterface
public interface UndatedEvents {

  record Page(String url, String title) {}

  UndatedEvents NONE = (since, limit) -> List.of();

  /** Undated events first discovered at or after {@code since}, newest first. */
  List<Page> discoveredSince(Instant since, int limit);
}
