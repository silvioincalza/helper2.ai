package ai.helper2.catalog;

import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.RecordRow;
import ai.helper2.catalog.internal.ResourceStore.ResourceRow;
import ai.helper2.search.MergedResult;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.SourceMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Stored resources as the same cards search shows; shared by the detail page and the home page lists. */
@Component
public class ResourceCards {

    private final ResourceStore store;
    private final ResourceCatalog catalog;
    private final ResultMerger merger = new ResultMerger();

    public ResourceCards(ResourceStore store, ResourceCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    /** The card of resource [id]; empty when it is gone or its records no longer merge. */
    public Optional<MergedResult> cardOf(UUID id) {
        return store.findResource(id).flatMap(row -> card(row, store.recordsOf(id)));
    }

    /**
     * One resource as a card: its source records merged, plus the date Helper2 first discovered it. The card
     * always carries the resource's own id: records may merge to another identity (a repository URL), and a
     * card with another id would link to a page that does not exist.
     */
    public Optional<MergedResult> card(ResourceRow row, List<RecordRow> records) {
        if (records.isEmpty()) {
            return Optional.empty();
        }
        Map<String, SourceDescriptor> descriptors = catalog.descriptors().stream()
            .collect(Collectors.toMap(SourceDescriptor::getId, d -> d, (a, b) -> a));

        Map<String, SourceContext> contexts = new HashMap<>();
        for (RecordRow r : records) {
            SourceDescriptor d = descriptors.get(r.sourceId());
            if (d != null) {
                // Data on a detail page comes from Helper2's store: INDEXED, unless curated or demo fixtures.
                SourceMode mode = d.getMode() == SourceMode.CURATED || d.getMode() == SourceMode.DEMO ? d.getMode() : SourceMode.INDEXED;
                contexts.put(r.sourceId(), new SourceContext(withMode(d, mode), ServedFrom.LIVE));
            }
        }
        List<MergedResult> merged = merger.merge(records.stream().map(RecordRow::hit).toList(), contexts);
        ResourceId id = new ResourceId(row.id());
        MergedResult card = merged.stream().filter(m -> m.getId().equals(id)).findFirst()
            .orElseGet(() -> merged.stream().max(Comparator.comparingInt(m -> m.getHits().size())).orElseThrow());
        CanonicalUrl canonical = Objects.requireNonNullElse(CanonicalUrl.of(row.canonicalUrl()), card.getCanonicalUrl());
        return Optional.of(card.withIdentity(id, canonical).withDiscovered(DateFact.observed(row.firstDiscoveredAt())));
    }

    private static SourceDescriptor withMode(SourceDescriptor d, SourceMode mode) {
        return Objects.equals(d.getMode(), mode) ? d : new SourceDescriptor(d.getId(), d.getName(), mode, d.getHomepage(),
            d.getTermsUrl(), d.getAttribution(), d.getResourceTypes(), d.getTimeout(), d.getCacheTtl(), d.getEnabled(),
            d.getPriority());
    }
}
