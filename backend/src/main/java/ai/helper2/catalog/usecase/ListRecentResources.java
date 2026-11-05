package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.ResourceCards;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.search.MergedResult;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** The home page's "Today" list. */
@Service
public class ListRecentResources {

  private final ResourceStore store;
  private final ResourceCards cards;

  public ListRecentResources(ResourceStore store, ResourceCards cards) {
    this.store = store;
    this.cards = cards;
  }

  /** The first cards of the list and how many resources the whole list has. */
  public record Page(List<MergedResult> items, int total) {}

  private static final Set<DataOrigin> INDEX = Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED);

  /**
   * Newest resources by source-stated publication date (the home page "Today" list), as the same
   * cards the detail page and search show, and the total for "Show all". Only Helper2's own index
   * (indexed and observed resources), never curated or demo rows; resources whose records no longer
   * merge are skipped (but counted). Seven statements for any limit.
   */
  public Page list(Instant since, Instant now, Set<String> types, int limit) {
    List<MergedResult> items =
        cards.cardsOf(store.recentlyPublished(since, now, types, INDEX, limit));
    return new Page(items, store.countRecentlyPublished(since, now, types, INDEX));
  }
}
