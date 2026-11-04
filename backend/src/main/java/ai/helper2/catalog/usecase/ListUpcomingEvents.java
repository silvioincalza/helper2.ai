package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.ResourceCards;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.search.MergedResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The home page's upcoming events.
 */
@Service
public class ListUpcomingEvents {

    private final ResourceStore store;
    private final ResourceCards cards;

    public ListUpcomingEvents(ResourceStore store, ResourceCards cards) {
        this.store = store;
        this.cards = cards;
    }

    /**
     * Events that have not ended by [today], soonest first, as the same cards search shows; six statements for any limit.
     */
    public List<MergedResult> list(Instant today, int limit) {
        return cards.cardsOf(store.upcomingEvents(today, limit));
    }
}
