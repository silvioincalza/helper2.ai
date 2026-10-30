package ai.helper2.scout.internal;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.scout.CheckedCitation;
import ai.helper2.scout.CheckedClaim;
import ai.helper2.scout.CheckedField;
import ai.helper2.scout.CheckedRelationship;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentMaterial;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentOutcome;
import ai.helper2.scout.EnrichmentStore;
import ai.helper2.scout.Material;
import ai.helper2.scout.ValidationResult;
import ai.helper2.shared.Verification;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads material from stored source records (title + description as retrieved) and persists
 * enrichment results with full history. Every field and relationship the validator did not reject
 * is written with its verification state (VERIFIED, UNVERIFIED or CONFLICTING), together with the
 * validated summary claims; readers decide what to show by that state. Every problem opens a review item.
 * A new run with model output replaces the AI-extracted fields of earlier runs, so a page never
 * mixes two runs. Runs themselves are kept for audit.
 */
@Component
class JdbcEnrichmentStore implements EnrichmentStore, EnrichmentBacklog {

    /** A FAILED run is usually transient (timeout, provider error); give up after this many. */
    static final int MAX_FAILED_ATTEMPTS = 3;

    /** Minimum source description length for a resource to be worth an LLM call. */
    static final int MIN_DESCRIPTION = 40;

    private final JdbcClient jdbc;
    private final ReviewQueue reviewQueue;
    private final ObjectMapper json;
    private final Clock clock;

    JdbcEnrichmentStore(JdbcClient jdbc, ReviewQueue reviewQueue, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.reviewQueue = reviewQueue;
        this.json = json;
        this.clock = clock;
    }

    /** Same text bar as {@link #pending}: a bare title gives the model nothing worth citing. */
    @Override
    public List<UUID> eligible(List<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> ok = new HashSet<>(jdbc.sql("""
                SELECT DISTINCT s.resource_id FROM source_record s
                WHERE s.resource_id = ANY(:ids) AND length(coalesce(s.description, '')) >= :minText
                """)
            .param("ids", resourceIds.toArray(UUID[]::new)).param("minText", MIN_DESCRIPTION)
            .query(UUID.class).list());
        return resourceIds.stream().filter(ok::contains).toList();
    }

    /**
     * Never enriched, enriched while no LLM was available, or whose last attempt FAILED (stored as
     * REJECTED) with retries left. Only resources with a source description: a bare title gives the
     * model nothing worth citing, so it would only produce REJECTED runs. Newest discoveries first.
     */
    @Override
    public List<UUID> pending(int limit) {
        return jdbc.sql("""
                SELECT r.id FROM resource r
                WHERE r.data_origin IN ('INDEXED', 'OBSERVED', 'CURATED')
                  AND EXISTS (SELECT 1 FROM source_record s
                              WHERE s.resource_id = r.id AND length(coalesce(s.description, '')) >= :minText)
                  AND (r.enrichment_status IN ('NOT_RUN', 'LLM_UNAVAILABLE')
                       OR (r.enrichment_status = 'REJECTED'
                           AND (SELECT e.outcome FROM enrichment_run e WHERE e.resource_id = r.id
                                ORDER BY e.started_at DESC LIMIT 1) = 'FAILED'
                           AND (SELECT count(*) FROM enrichment_run e
                                WHERE e.resource_id = r.id AND e.outcome = 'FAILED') < :maxFailed))
                ORDER BY (r.enrichment_status = 'NOT_RUN') DESC, r.first_discovered_at DESC
                LIMIT :limit
                """)
            .param("maxFailed", MAX_FAILED_ATTEMPTS).param("minText", MIN_DESCRIPTION).param("limit", limit)
            .query(UUID.class).list();
    }

    /**
     * The source records as material, and what the sources themselves state (licence, type, open source),
     * which the model may not overwrite. A type of OTHER says nothing, so it is no fact.
     */
    @Override
    public EnrichmentMaterial load(UUID resourceId) {
        record Row(UUID id, String url, Instant retrieved, String text) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT id, source_url, retrieved_at, title || E'\\n' || coalesce(description, '') AS text
                FROM source_record WHERE resource_id = :id ORDER BY source_id
                """).param("id", resourceId)
            .query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4)))
            .list();
        if (rows.isEmpty()) {
            return null;
        }
        record Resource(String title, String type, Boolean openSource) {
        }
        Resource resource = jdbc.sql("SELECT title, type, open_source FROM resource WHERE id = :id").param("id", resourceId)
            .query((rs, i) -> new Resource(rs.getString(1), rs.getString(2), (Boolean) rs.getObject(3))).single();
        List<Material> materials = rows.stream()
            .map(r -> new Material(r.id(), URI.create(r.url()), r.retrieved(), r.text())).toList();
        Map<String, String> facts = new HashMap<>();
        jdbc.sql("SELECT license FROM source_record WHERE resource_id = :id AND license IS NOT NULL ORDER BY source_id LIMIT 1")
            .param("id", resourceId).query(String.class).optional().ifPresent(l -> facts.put("license", l));
        if (!"OTHER".equals(resource.type())) {
            facts.put("type", resource.type());
        }
        if (resource.openSource() != null) {
            facts.put("openSource", resource.openSource().toString());
        }
        String allText = String.join("\n", rows.stream().map(Row::text).toList());
        return new EnrichmentMaterial(new EnrichmentInput(resource.title(), materials, facts, Map.of()), sha(allText));
    }

    /** Runs without a judged answer (LLM_UNAVAILABLE, FAILED) say nothing about the input, so they are passed over. */
    @Override
    public String lastFingerprint(UUID resourceId) {
        return jdbc.sql("""
                SELECT input_fingerprint FROM enrichment_run
                WHERE resource_id = :id AND outcome NOT IN ('LLM_UNAVAILABLE', 'FAILED')
                ORDER BY started_at DESC LIMIT 1
                """).param("id", resourceId).query(String.class).optional().orElse(null);
    }

    /**
     * Exact, case-insensitive title match on the few targets the model named, never a scan of every title
     * against the material. Titles under 4 characters ("AI", "Go") are too ambiguous to link. For duplicate
     * titles the earliest discovered resource wins.
     */
    @Override
    public Map<String, UUID> resourcesTitled(Collection<String> titles, UUID resourceId) {
        String[] wanted = titles.stream().map(t -> t.strip().toLowerCase()).filter(t -> t.length() >= 4).distinct()
            .toArray(String[]::new);
        if (wanted.length == 0) {
            return Map.of();
        }
        Map<String, UUID> known = new HashMap<>();
        jdbc.sql("""
                SELECT DISTINCT ON (lower(title)) lower(title), id FROM resource
                WHERE id <> :id AND lower(title) = ANY(:titles)
                ORDER BY lower(title), first_discovered_at, id
                """).param("id", resourceId).param("titles", wanted)
            .query((rs, i) -> known.put(rs.getString(1), rs.getObject(2, UUID.class))).list();
        return known;
    }

    @Override
    @Transactional
    public void save(UUID resourceId, EnrichmentModel model, String inputFingerprint, Instant startedAt,
                     ValidationResult result, EnrichmentOutcome outcome, String failure) {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint,
                                            started_at, finished_at, outcome, validation_errors)
                VALUES (:id, :rid, :provider, :model, :pv, :fp, :started, :finished, :outcome, CAST(:errors AS jsonb))
                """)
            .param("id", runId).param("rid", resourceId).param("provider", model.getProvider()).param("model", model.getModel())
            .param("pv", model.getPromptVersion()).param("fp", inputFingerprint).param("started", Timestamp.from(startedAt))
            .param("finished", Timestamp.from(clock.instant())).param("outcome", outcome.name())
            .param("errors", json.writeValueAsString(result != null ? result.getErrors() : failure != null ? List.of(failure) : List.of()))
            .update();
        jdbc.sql("UPDATE resource SET enrichment_status = :s WHERE id = :id")
            .param("s", outcome == EnrichmentOutcome.FAILED ? "REJECTED" : outcome.name()).param("id", resourceId).update();
        if (result == null) {
            return;
        }
        deleteAiFields(resourceId);
        Map<String, UUID> evidence = new HashMap<>();
        for (CheckedField f : result.getFields()) {
            if (f.getVerification() == Verification.REJECTED) {
                continue;
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO field_assertion (id, resource_id, field, value, origin, verification, enrichment_run_id)
                    VALUES (:id, :rid, :f, :v, 'AI_EXTRACTED', :ver, :run)
                    """).param("id", id).param("rid", resourceId).param("f", f.getField()).param("v", f.getValue())
                .param("ver", f.getVerification().name()).param("run", runId).update();
            link("field_assertion_evidence", "assertion_id", id, f.getCitations(), evidence);
        }
        for (CheckedRelationship r : result.getRelationships()) {
            if (r.getVerification() == Verification.REJECTED) {
                continue;
            }
            UUID id = UUID.randomUUID();
            int inserted = jdbc.sql("""
                    INSERT INTO relationship (id, from_resource_id, to_resource_id, to_title, kind, verification, origin, enrichment_run_id)
                    VALUES (:id, :from, :to, :title, :kind, :ver, 'AI_EXTRACTED', :run)
                    ON CONFLICT (from_resource_id, kind, to_title) DO NOTHING
                    """).param("id", id).param("from", resourceId).param("to", r.getTargetId()).param("title", r.getTarget())
                .param("kind", r.getKind()).param("ver", r.getVerification().name()).param("run", runId).update();
            if (inserted == 0) {
                continue;
            }
            link("relationship_evidence", "relationship_id", id, r.getCitations(), evidence);
            if (r.getVerification() != Verification.VERIFIED) {
                reviewQueue.open(ReviewQueue.Kind.RELATIONSHIP_CANDIDATE, resourceId,
                    "Unverified relationship: " + r.getKind() + " " + r.getTarget(),
                    "Evidence supports the text, but the target is not a known resource.",
                    new ReviewQueue.Links(runId, id, null));
            }
        }
        int pos = 0;
        for (CheckedClaim c : result.getSummary()) {
            UUID id = UUID.randomUUID();
            jdbc.sql("INSERT INTO summary_claim (id, resource_id, enrichment_run_id, position, text) VALUES (:id, :rid, :run, :p, :t)")
                .param("id", id).param("rid", resourceId).param("run", runId).param("p", pos++).param("t", c.getText()).update();
            link("summary_claim_evidence", "claim_id", id, c.getCitations(), evidence);
        }
        if (!result.getErrors().isEmpty() || result.getFields().stream().anyMatch(f -> f.getVerification() == Verification.CONFLICTING)) {
            reviewQueue.open(ReviewQueue.Kind.ENRICHMENT_VALIDATION, resourceId,
                "Enrichment " + outcome.name().toLowerCase() + ": " + result.getErrors().size() + " validation issue(s)",
                String.join("\n", result.getErrors().stream().limit(20).toList()), new ReviewQueue.Links(runId, null, null));
        }
    }

    /**
     * Removes the resource's AI-extracted fields, and the evidence rows that only they used (the links go
     * with the fields by cascade, the evidence itself would stay behind). Evidence a relationship or a
     * summary claim still cites is kept.
     */
    private void deleteAiFields(UUID resourceId) {
        // The CTE reads the links before the cascade removes them (one snapshot per statement).
        List<UUID> cited = jdbc.sql("""
                WITH gone AS (DELETE FROM field_assertion WHERE resource_id = :rid AND origin = 'AI_EXTRACTED' RETURNING id)
                SELECT DISTINCT fae.evidence_id FROM field_assertion_evidence fae JOIN gone ON gone.id = fae.assertion_id
                """).param("rid", resourceId).query(UUID.class).list();
        if (cited.isEmpty()) {
            return;
        }
        jdbc.sql("""
                DELETE FROM evidence e WHERE e.id IN (:ids)
                  AND NOT EXISTS (SELECT 1 FROM field_assertion_evidence x WHERE x.evidence_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM relationship_evidence x WHERE x.evidence_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM summary_claim_evidence x WHERE x.evidence_id = e.id)
                """).param("ids", cited).update();
    }

    /** One evidence row per (source record, excerpt), linked to the owner. joinTable/fk are constants. */
    private void link(String joinTable, String fk, UUID owner, List<CheckedCitation> citations, Map<String, UUID> cache) {
        for (CheckedCitation c : citations) {
            String key = c.getEvidenceId() + "|" + c.getExcerpt();
            UUID evidenceId = cache.computeIfAbsent(key, k -> {
                UUID id = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO evidence (id, source_record_id, source_url, retrieved_at, excerpt)
                        SELECT :id, sr.id, sr.source_url, sr.retrieved_at, :excerpt FROM source_record sr WHERE sr.id = :rec
                        """).param("id", id).param("rec", c.getEvidenceId()).param("excerpt", c.getExcerpt()).update();
                return id;
            });
            jdbc.sql("INSERT INTO %s (%s, evidence_id) VALUES (:o, :e) ON CONFLICT DO NOTHING".formatted(joinTable, fk))
                .param("o", owner).param("e", evidenceId).update();
        }
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
