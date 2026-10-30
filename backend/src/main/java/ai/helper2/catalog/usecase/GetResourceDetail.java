package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.internal.JdbcEnrichmentResults;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.RecordRow;
import ai.helper2.catalog.internal.ResourceStore.ResourceRow;
import ai.helper2.search.FieldConflict;
import ai.helper2.search.MergedResult;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** The resource detail page: every fact with its origin, verification and evidence. */
@Service
public class GetResourceDetail {

    private final ResourceStore store;
    private final JdbcEnrichmentResults enrichment;
    private final ResourceCards cards;

    public GetResourceDetail(ResourceStore store, JdbcEnrichmentResults enrichment, ResourceCards cards) {
        this.store = store;
        this.enrichment = enrichment;
        this.cards = cards;
    }

    /**
     * The detail page of [id]. A resource is what its source records say, so it is looked up through them:
     * an id whose records all moved to another resource (an npm package later merged into its GitHub
     * repository) resolves to that resource.
     */
    public Optional<ResourceDetail> find(UUID id) {
        List<RecordRow> records = store.recordsOf(id);
        if (records.isEmpty()) {
            return store.ownerOfRecordUrl(id).filter(owner -> !owner.equals(id))
                .flatMap(owner -> detail(owner, store.recordsOf(owner)));
        }
        return detail(id, records);
    }

    private Optional<ResourceDetail> detail(UUID id, List<RecordRow> records) {
        Optional<ResourceRow> row = store.findResource(id);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Optional<MergedResult> merged = cards.card(row.get(), records);
        if (merged.isEmpty()) {
            return Optional.empty();
        }
        MergedResult card = merged.get();
        List<ResourceDetail.Assertion> assertions = new ArrayList<>(sourceAssertions(records, card.getConflicts()));
        assertions.addAll(enrichment.assertions(id));
        return Optional.of(new ResourceDetail(card, assertions, enrichment.relationships(id), enrichment.summary(id),
            records.stream().map(r -> new ResourceDetail.SourceRecordInfo(r.sourceId(), r.externalId(), URI.create(r.sourceUrl()),
                r.retrievedAt(), r.fingerprint())).toList(),
            row.get().enrichmentStatus()));
    }

    /** Source-provided facts: VERIFIED means "this is exactly what the source returned", with the record as evidence. */
    private List<ResourceDetail.Assertion> sourceAssertions(List<RecordRow> records, List<FieldConflict> conflicts) {
        Set<String> conflicting = conflicts.stream().map(FieldConflict::getField).collect(Collectors.toSet());
        List<ResourceDetail.Assertion> out = new ArrayList<>();
        for (RecordRow r : records) {
            SourceHit h = r.hit();
            ResourceDetail.Evidence ev = new ResourceDetail.Evidence(r.id(), URI.create(r.sourceUrl()), r.retrievedAt(), null);
            ValueOrigin origin = h.getDescriptionOrigin() == ValueOrigin.CURATED ? ValueOrigin.CURATED : ValueOrigin.SOURCE_PROVIDED;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("title", h.getTitle());
            fields.put("description", h.getDescription());
            // "type" is omitted: for most sources it is assigned by Helper2 rules, not stated by the source.
            fields.put("license", h.getLicense());
            fields.put("published", h.getPublished().getValue() == null ? null : h.getPublished().getValue().toString());
            fields.put("updated", h.getUpdated().getValue() == null ? null : h.getUpdated().getValue().toString());
            fields.forEach((field, value) -> {
                if (value != null) {
                    Verification v = conflicting.contains(field) ? Verification.CONFLICTING : Verification.VERIFIED;
                    out.add(new ResourceDetail.Assertion(field, value, origin, v, r.sourceId(), List.of(ev)));
                }
            });
        }
        return out;
    }
}
