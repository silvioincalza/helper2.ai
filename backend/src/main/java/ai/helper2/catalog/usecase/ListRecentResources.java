package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.search.MergedResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    /**
     * Newest resources by source-stated publication date (the home page "Today" list), as the same cards
     * the detail page and search show. Only Helper2's own index (indexed and observed resources), never
     * curated or demo rows; resources whose records no longer merge are skipped.
     */
    public List<MergedResult> list(Instant since, Instant now, Set<String> types, int limit) {
        List<MergedResult> out = new ArrayList<>();
        for (UUID id : store.recentlyPublished(since, now, types, Set.of(DataOrigin.INDEXED, DataOrigin.OBSERVED), limit)) {
            cards.cardOf(id).ifPresent(out::add);
        }
        return out;
    }
}
