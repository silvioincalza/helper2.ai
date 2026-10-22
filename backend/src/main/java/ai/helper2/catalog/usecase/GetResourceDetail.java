package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.ResourceDetail;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The resource detail page: every fact with its origin, verification and evidence. */
@Service
public class GetResourceDetail {

    private final ResourceStore store;
    private final JdbcClient jdbc;
    private final ResourceCards cards;

    public GetResourceDetail(ResourceStore store, JdbcClient jdbc, ResourceCards cards) {
        this.store = store;
        this.jdbc = jdbc;
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
        assertions.addAll(aiAssertions(id));
        return Optional.of(new ResourceDetail(card, assertions, relationships(id), summary(id),
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

    private List<ResourceDetail.Assertion> aiAssertions(UUID id) {
        return jdbc.sql("SELECT id, field, value, origin, verification FROM field_assertion WHERE resource_id = :id ORDER BY field")
            .param("id", id)
            .query((rs, n) -> new ResourceDetail.Assertion(rs.getString("field"), rs.getString("value"),
                ValueOrigin.valueOf(rs.getString("origin")), Verification.valueOf(rs.getString("verification")), null,
                evidence("field_assertion_evidence", "assertion_id", rs.getObject("id", UUID.class))))
            .list();
    }

    private List<ResourceDetail.Relationship> relationships(UUID id) {
        return jdbc.sql("""
                SELECT id, kind, to_resource_id, to_title, verification FROM relationship
                WHERE from_resource_id = :id AND verification <> 'REJECTED' ORDER BY kind, to_title
                """)
            .param("id", id)
            .query((rs, n) -> new ResourceDetail.Relationship(rs.getString("kind"), rs.getObject("to_resource_id", UUID.class),
                rs.getString("to_title"), Verification.valueOf(rs.getString("verification")),
                evidence("relationship_evidence", "relationship_id", rs.getObject("id", UUID.class))))
            .list();
    }

    private ResourceDetail.Summary summary(UUID id) {
        return jdbc.sql("""
                SELECT r.id, r.model, r.prompt_version, r.finished_at FROM enrichment_run r
                WHERE r.resource_id = :id AND r.outcome IN ('VALIDATED','PARTIAL')
                  AND EXISTS (SELECT 1 FROM summary_claim c WHERE c.enrichment_run_id = r.id)
                ORDER BY r.started_at DESC LIMIT 1
                """)
            .param("id", id)
            .query((rs, n) -> {
                UUID run = rs.getObject("id", UUID.class);
                List<ResourceDetail.SummaryClaim> claims = jdbc.sql("SELECT id, text FROM summary_claim WHERE enrichment_run_id = :run ORDER BY position")
                    .param("run", run)
                    .query((c, m) -> new ResourceDetail.SummaryClaim(c.getString("text"),
                        evidence("summary_claim_evidence", "claim_id", c.getObject("id", UUID.class))))
                    .list();
                return new ResourceDetail.Summary(claims, rs.getString("model"), rs.getString("prompt_version"),
                    rs.getTimestamp("finished_at").toInstant());
            })
            .optional().orElse(null);
    }

    private List<ResourceDetail.Evidence> evidence(String joinTable, String fk, UUID owner) {
        // joinTable/fk are compile-time constants from this class, never user input.
        return jdbc.sql("""
                SELECT e.id, e.source_url, e.retrieved_at, e.excerpt FROM evidence e
                JOIN %s j ON j.evidence_id = e.id WHERE j.%s = :owner ORDER BY e.retrieved_at
                """.formatted(joinTable, fk))
            .param("owner", owner)
            .query((rs, n) -> new ResourceDetail.Evidence(rs.getObject("id", UUID.class), URI.create(rs.getString("source_url")),
                rs.getTimestamp("retrieved_at").toInstant(), rs.getString("excerpt")))
            .list();
    }
}
