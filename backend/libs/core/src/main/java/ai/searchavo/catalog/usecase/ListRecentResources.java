package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.ResourceCards;
import ai.searchavo.catalog.Resources;
import ai.searchavo.search.MergedResult;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public class ListRecentResources {

  private final Resources store;
  private final ResourceCards cards;

  public ListRecentResources(Resources store, ResourceCards cards) {
    this.store = store;
    this.cards = cards;
  }

  public record Page(List<MergedResult> items, int total) {}

  private static final Set<DataOrigin> INDEX = Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED);

  public Page list(Instant since, Instant now, Set<String> types, int limit) {
    List<MergedResult> items =
        cards.cardsOf(store.recentlyPublished(since, now, types, INDEX, limit));
    return new Page(items, store.countRecentlyPublished(since, now, types, INDEX));
  }
}
