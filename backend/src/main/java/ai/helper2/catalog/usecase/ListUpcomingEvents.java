package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.search.MergedResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    /** Events that have not ended by [today], soonest first, as the same cards search shows. */
    public List<MergedResult> list(Instant today, int limit) {
        List<MergedResult> out = new ArrayList<>();
        for (UUID id : store.upcomingEvents(today, limit)) {
            cards.cardOf(id).ifPresent(out::add);
        }
        return out;
    }
}
