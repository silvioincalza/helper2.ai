package ai.helper2.catalog.usecase;

import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
import ai.helper2.search.MergedResult;
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
