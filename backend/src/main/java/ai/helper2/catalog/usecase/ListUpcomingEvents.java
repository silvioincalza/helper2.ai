package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.ResourceCards;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.search.MergedResult;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** The home page's upcoming events. */
@Service
public class ListUpcomingEvents {

  private final ResourceStore store;
  private final ResourceCards cards;

  public ListUpcomingEvents(ResourceStore store, ResourceCards cards) {
    this.store = store;
    this.cards = cards;
  }

  /** The first cards of the list and how many events the whole list has. */
  public record Page(List<MergedResult> items, int total) {}

  /**
   * Events that have not ended by [today] and, with [until] (null: no bound), start before it,
   * soonest first, as the same cards search shows, and the total for "Show all". Seven statements
   * for any limit.
   */
  public Page list(Instant today, @Nullable Instant until, int limit) {
    List<MergedResult> items = cards.cardsOf(store.upcomingEvents(today, until, limit));
    return new Page(items, store.countUpcomingEvents(today, until));
  }
}
