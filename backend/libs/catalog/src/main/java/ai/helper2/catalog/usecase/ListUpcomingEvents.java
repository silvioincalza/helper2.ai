package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
import ai.helper2.search.MergedResult;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public class ListUpcomingEvents {

  private final Resources store;
  private final ResourceCards cards;

  public ListUpcomingEvents(Resources store, ResourceCards cards) {
    this.store = store;
    this.cards = cards;
  }

  public record Page(List<MergedResult> items, int total) {}

  public Page list(Instant today, @Nullable Instant until, int limit) {
    List<MergedResult> items = cards.cardsOf(store.upcomingEvents(today, until, limit));
    return new Page(items, store.countUpcomingEvents(today, until));
  }
}
