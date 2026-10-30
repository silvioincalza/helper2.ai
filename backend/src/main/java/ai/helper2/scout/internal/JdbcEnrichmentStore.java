package ai.helper2.scout.internal;

import ai.helper2.catalog.EnrichmentResults;
import ai.helper2.scout.CheckedCitation;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentMaterial;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentOutcome;
import ai.helper2.scout.EnrichmentStore;
import ai.helper2.scout.Material;
import ai.helper2.scout.ValidationResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

/**
 * Loads material from stored source records (title + description as retrieved) and hands each run's checked
 * answer to the catalog ({@link EnrichmentResults}), which owns how it is stored.
 */
@Component
class JdbcEnrichmentStore implements EnrichmentStore, EnrichmentBacklog {

    /** A FAILED run is usually transient (timeout, provider error); give up after this many. */
    static final int MAX_FAILED_ATTEMPTS = 3;

    /** Minimum source description length for a resource to be worth an LLM call. */
    static final int MIN_DESCRIPTION = 40;

    private final JdbcClient jdbc;
    private final EnrichmentResults results;

    JdbcEnrichmentStore(JdbcClient jdbc, EnrichmentResults results) {
        this.jdbc = jdbc;
        this.results = results;
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
    public void save(UUID resourceId, EnrichmentModel model, String inputFingerprint, Instant startedAt,
                     ValidationResult result, EnrichmentOutcome outcome, String failure) {
        List<String> errors = result != null ? result.getErrors() : failure != null ? List.of(failure) : List.of();
        results.record(new EnrichmentResults.Run(resourceId, model.getProvider(), model.getModel(), model.getPromptVersion(),
            inputFingerprint, startedAt, outcome.name(), errors), result == null ? null : checked(result));
    }

    private static EnrichmentResults.Checked checked(ValidationResult result) {
        return new EnrichmentResults.Checked(
            result.getFields().stream().map(f -> new EnrichmentResults.Field(f.getField(), f.getValue(), f.getVerification(),
                citations(f.getCitations()))).toList(),
            result.getRelationships().stream().map(r -> new EnrichmentResults.Relationship(r.getKind(), r.getTarget(), r.getTargetId(),
                r.getVerification(), citations(r.getCitations()))).toList(),
            result.getSummary().stream().map(c -> new EnrichmentResults.Claim(c.getText(), citations(c.getCitations()))).toList());
    }

    private static List<EnrichmentResults.Citation> citations(List<CheckedCitation> citations) {
        return citations.stream().map(c -> new EnrichmentResults.Citation(c.getEvidenceId(), c.getExcerpt())).toList();
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
