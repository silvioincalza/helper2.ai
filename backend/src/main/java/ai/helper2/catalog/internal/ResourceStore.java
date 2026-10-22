package ai.helper2.catalog.internal;

import ai.helper2.search.MergedResult;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.DatePrecision;
import ai.helper2.shared.LanguageDetector;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.TechLevel;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** JDBC access to resources and source records. All SQL for the catalog's core tables lives here. */
@Repository
public class ResourceStore {

    /** Origin precedence: a higher-precedence origin's descriptive fields are never overwritten by a lower one. */
    public enum DataOrigin { OBSERVED, INDEXED, CURATED, DEMO }

    /**
     * A catalog row with its labels and the popularity metrics its source records state (each still
     * attributed to the source that reported it).
     */
    public record ResourceRow(
        UUID id, String canonicalUrl, String url, String title, String description, ValueOrigin descriptionOrigin,
        ResourceType type, Audience audience, TechLevel techLevel, String license, Pricing pricing, Boolean openSource,
        Instant publishedAt, DatePrecision publishedPrecision, String publishedSourceId,
        Instant updatedAt, String updatedSourceId, Instant firstDiscoveredAt, Instant lastSeenAt,
        DataOrigin dataOrigin, String enrichmentStatus, Set<String> tags, Set<String> useCases, Set<String> platforms,
        List<PopularityMetric> popularity) {

        ResourceRow withFacts(Map<String, Set<String>> labels, List<PopularityMetric> metrics) {
            return new ResourceRow(id, canonicalUrl, url, title, description, descriptionOrigin, type, audience, techLevel,
                license, pricing, openSource, publishedAt, publishedPrecision, publishedSourceId, updatedAt, updatedSourceId,
                firstDiscoveredAt, lastSeenAt, dataOrigin, enrichmentStatus, labels.getOrDefault("TAG", Set.of()),
                labels.getOrDefault("USE_CASE", Set.of()), labels.getOrDefault("PLATFORM", Set.of()), metrics);
        }
    }

    public record RecordRow(UUID id, String sourceId, String externalId, UUID resourceId, String sourceUrl,
                            Instant retrievedAt, String fingerprint, SourceHit hit) {
    }

    private static final String ORIGIN_RANK =
        "(CASE %s WHEN 'DEMO' THEN 3 WHEN 'CURATED' THEN 2 WHEN 'INDEXED' THEN 1 ELSE 0 END)";
    /** Tags kept per resource (labels are rebuilt from its records on every upsert). */
    static final int MAX_TAGS = 20;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final ObjectMapper json;
    private final LanguageDetector languages;

    public ResourceStore(JdbcClient jdbc, JdbcTemplate template, ObjectMapper json, LanguageDetector languages) {
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
        this.languages = languages;
    }

    @Transactional
    public void registerSources(Collection<SourceDescriptor> descriptors) {
        for (SourceDescriptor d : descriptors) {
            jdbc.sql("""
                    INSERT INTO source (id, name, mode, homepage, terms_url, attribution, enabled)
                    VALUES (:id, :name, :mode, :homepage, :terms, :attribution, :enabled)
                    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, mode = EXCLUDED.mode,
                        homepage = EXCLUDED.homepage, terms_url = EXCLUDED.terms_url,
                        attribution = EXCLUDED.attribution, enabled = EXCLUDED.enabled, modified_at = now()
                    """)
                .param("id", d.getId()).param("name", d.getName()).param("mode", d.getMode().name())
                .param("homepage", d.getHomepage().toString())
                .param("terms", d.getTermsUrl() == null ? null : d.getTermsUrl().toString())
                .param("attribution", d.getAttribution()).param("enabled", d.getEnabled())
                .update();
        }
    }

    public record RecordedOutcome(String sourceId, String outcome, Instant checkedAt) {}

    /** Last outcome per source as written by the Scout; sources never run are absent. */
    public List<RecordedOutcome> recordedOutcomes() {
        return jdbc.sql("SELECT id, last_outcome, last_checked_at FROM source WHERE last_outcome IS NOT NULL AND last_checked_at IS NOT NULL")
            .query((rs, i) -> new RecordedOutcome(rs.getString("id"), rs.getString("last_outcome"),
                rs.getTimestamp("last_checked_at").toInstant()))
            .list();
    }

    /** A source record to write: the hit, its stable id and the resource that owns it. */
    private record RecordInput(UUID id, SourceHit hit, String externalId, UUID owner) {
    }

    /**
     * Upserts merged results and the source records behind them (those of [persistableSources] only).
     * Returns the record ids whose content fingerprint changed (or are new), for the Scout's change detection.
     *
     * Then, for every resource touched (owners, and resources a record moved away from): labels are rebuilt
     * from the current records, event dates follow the records, and an OBSERVED resource left without any
     * record is deleted (its links still resolve through the record, see [ownerOfRecordUrl]). Its user reports
     * and review items move to the resource its records moved to first, so no report is lost.
     * Rows are written in canonical-URL / record-key order, so concurrent writers lock them in the same order.
     */
    @Transactional
    public List<UUID> upsert(List<MergedResult> results, DataOrigin origin, Set<String> persistableSources) {
        Map<String, Object[]> resourceArgs = new TreeMap<>();
        Map<String, RecordInput> records = new TreeMap<>();
        for (MergedResult r : results) {
            List<SourceHit> own = r.getHits().stream().filter(h -> persistableSources.contains(h.getSourceId())).toList();
            if (own.isEmpty()) {
                continue;
            }
            resourceArgs.put(r.getCanonicalUrl().getValue(), resourceArgs(r, origin));
            for (SourceHit h : own) {
                String externalId = clip(h.getExternalId(), 300);
                UUID recordId = UUID.nameUUIDFromBytes((h.getSourceId() + "|" + h.getExternalId()).getBytes());
                // A source listing the same item twice must not hit one row twice in one statement.
                records.put(h.getSourceId() + "\u001f" + externalId, new RecordInput(recordId, h, externalId, r.getId().getValue()));
            }
        }
        if (resourceArgs.isEmpty()) {
            return List.of();
        }
        upsertResources(new ArrayList<>(resourceArgs.values()));

        List<UUID> changed = new ArrayList<>();
        Set<UUID> owners = new HashSet<>();
        List<UpsertedRecord> moves = new ArrayList<>();
        for (RecordInput in : records.values()) {
            owners.add(in.owner());
        }
        upsertRecords(records.values()).forEach(row -> {
            if (row.changed()) {
                changed.add(row.id());
            }
            if (row.previousOwner() != null && !row.previousOwner().equals(row.owner())) {
                moves.add(row);
            }
        });
        Set<UUID> movedFrom = new HashSet<>();
        moves.forEach(m -> movedFrom.add(m.previousOwner()));
        moveReportsOfEmptiedResources(moves);
        deleteOrphans(movedFrom);
        Set<UUID> touched = new HashSet<>(owners);
        touched.addAll(movedFrom);
        replaceLabels(touched);
        refreshEventDates(touched);
        return changed;
    }

    private void upsertResources(List<Object[]> args) {
        String rank = ORIGIN_RANK.formatted("resource.data_origin");
        String newRank = ORIGIN_RANK.formatted("EXCLUDED.data_origin");
        String keep = rank + " > " + newRank;
        template.batchUpdate("""
                INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience,
                    tech_level, license, pricing, open_source, published_at, published_precision, published_source_id,
                    updated_at, updated_source_id, data_origin, language)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (canonical_url) DO UPDATE SET
                    last_seen_at = now(),
                    modified_at = now(),
                    url = CASE WHEN %1$s THEN resource.url ELSE EXCLUDED.url END,
                    title = CASE WHEN %1$s THEN resource.title ELSE EXCLUDED.title END,
                    description = CASE WHEN %1$s THEN resource.description ELSE coalesce(EXCLUDED.description, resource.description) END,
                    description_origin = CASE WHEN %1$s OR EXCLUDED.description IS NULL THEN resource.description_origin ELSE EXCLUDED.description_origin END,
                    type = CASE WHEN %1$s THEN resource.type ELSE EXCLUDED.type END,
                    audience = CASE WHEN %1$s THEN resource.audience ELSE EXCLUDED.audience END,
                    tech_level = coalesce(resource.tech_level, EXCLUDED.tech_level),
                    license = CASE WHEN %1$s THEN resource.license ELSE EXCLUDED.license END,
                    pricing = CASE WHEN %1$s THEN resource.pricing ELSE EXCLUDED.pricing END,
                    open_source = CASE WHEN %1$s THEN resource.open_source ELSE EXCLUDED.open_source END,
                    published_at = CASE WHEN %1$s OR EXCLUDED.published_at IS NULL THEN resource.published_at ELSE EXCLUDED.published_at END,
                    published_precision = CASE WHEN %1$s OR EXCLUDED.published_at IS NULL THEN resource.published_precision ELSE EXCLUDED.published_precision END,
                    published_source_id = CASE WHEN %1$s OR EXCLUDED.published_at IS NULL THEN resource.published_source_id ELSE EXCLUDED.published_source_id END,
                    updated_at = CASE WHEN EXCLUDED.updated_at IS NOT NULL AND (resource.updated_at IS NULL OR EXCLUDED.updated_at > resource.updated_at)
                                      THEN EXCLUDED.updated_at ELSE resource.updated_at END,
                    updated_source_id = CASE WHEN EXCLUDED.updated_at IS NOT NULL AND (resource.updated_at IS NULL OR EXCLUDED.updated_at > resource.updated_at)
                                      THEN EXCLUDED.updated_source_id ELSE resource.updated_source_id END,
                    data_origin = CASE WHEN %1$s THEN resource.data_origin ELSE EXCLUDED.data_origin END,
                    language = CASE WHEN %1$s THEN resource.language ELSE EXCLUDED.language END
                """.formatted(keep), args);
    }

    private record UpsertedRecord(UUID id, UUID owner, boolean changed, UUID previousOwner) {
    }

    /**
     * Every record in one statement. The rows travel as one JSON array (jsonb_to_recordset), so the batch
     * is a single round trip whatever its size; the CTE "old" sees the rows as they were before the insert.
     */
    private List<UpsertedRecord> upsertRecords(Collection<RecordInput> records) {
        ArrayNode rows = json.createArrayNode();
        for (RecordInput in : records) {
            SourceHit h = in.hit();
            ObjectNode n = rows.addObject();
            n.put("id", in.id().toString());
            n.put("source_id", h.getSourceId());
            n.put("external_id", in.externalId());
            n.put("resource_id", in.owner().toString());
            n.put("source_url", h.getUrl().toString());
            n.put("canonical_url", h.getCanonicalUrl().getValue());
            n.put("repository_url", h.getRepositoryUrl() == null ? null : h.getRepositoryUrl().getValue());
            n.put("title", clip(h.getTitle(), 300));
            n.put("description", clip(h.getDescription(), 4000));
            n.put("type", h.getType().name());
            n.put("license", h.getLicense());
            n.put("source_published_at", iso(h.getPublished().getValue()));
            n.put("source_updated_at", iso(h.getUpdated().getValue()));
            n.put("source_event_starts_at", iso(h.getEventStart().getValue()));
            n.put("source_event_ends_at", iso(h.getEventEnd().getValue()));
            n.put("retrieved_at", iso(h.getRetrievedAt()));
            n.put("fingerprint", Fingerprints.of(h));
            n.set("extra", extra(h));
        }
        return jdbc.sql("""
                WITH input AS (
                  SELECT * FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS t(
                    id uuid, source_id text, external_id text, resource_id uuid, source_url text, canonical_url text,
                    repository_url text, title text, description text, type text, license text,
                    source_published_at timestamptz, source_updated_at timestamptz, source_event_starts_at timestamptz,
                    source_event_ends_at timestamptz, retrieved_at timestamptz, fingerprint text, extra jsonb)
                ), old AS (
                  SELECT sr.source_id, sr.external_id, sr.fingerprint, sr.resource_id
                  FROM source_record sr JOIN input i ON i.source_id = sr.source_id AND i.external_id = sr.external_id
                ), up AS (
                  INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url,
                      repository_url, title, description, type, license, source_published_at, source_updated_at,
                      source_event_starts_at, source_event_ends_at, retrieved_at, fingerprint, extra)
                  SELECT id, source_id, external_id, resource_id, source_url, canonical_url, repository_url, title,
                         description, type, license, source_published_at, source_updated_at, source_event_starts_at,
                         source_event_ends_at, retrieved_at, fingerprint, extra
                  FROM input
                  ON CONFLICT (source_id, external_id) DO UPDATE SET
                      resource_id = EXCLUDED.resource_id, source_url = EXCLUDED.source_url,
                      canonical_url = EXCLUDED.canonical_url, repository_url = EXCLUDED.repository_url,
                      title = EXCLUDED.title, description = EXCLUDED.description, type = EXCLUDED.type,
                      license = EXCLUDED.license, source_published_at = EXCLUDED.source_published_at,
                      source_updated_at = EXCLUDED.source_updated_at, retrieved_at = EXCLUDED.retrieved_at,
                      source_event_starts_at = EXCLUDED.source_event_starts_at, source_event_ends_at = EXCLUDED.source_event_ends_at,
                      fingerprint = EXCLUDED.fingerprint, extra = EXCLUDED.extra
                  RETURNING id, source_id, external_id, resource_id, fingerprint
                )
                SELECT up.id, up.resource_id, old.fingerprint IS DISTINCT FROM up.fingerprint AS changed,
                       old.resource_id AS previous_owner
                FROM up LEFT JOIN old ON old.source_id = up.source_id AND old.external_id = up.external_id
                """)
            .param("rows", json.writeValueAsString(rows))
            .query((rs, i) -> new UpsertedRecord(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class),
                rs.getBoolean("changed"), rs.getObject("previous_owner", UUID.class)))
            .list();
    }

    /**
     * A resource whose records all moved to one other resource is now that resource: its user reports and review
     * items follow. When its records went to several resources, the owner is ambiguous, so the reports stay and
     * [deleteOrphans] keeps the resource for the reviewer.
     */
    private void moveReportsOfEmptiedResources(List<UpsertedRecord> moves) {
        if (moves.isEmpty()) {
            return;
        }
        UUID[] from = moves.stream().map(UpsertedRecord::previousOwner).toArray(UUID[]::new);
        UUID[] to = moves.stream().map(UpsertedRecord::owner).toArray(UUID[]::new);
        String target = """
                WITH moved AS (SELECT * FROM unnest(CAST(:from AS uuid[]), CAST(:to AS uuid[])) AS m(previous_owner, owner)),
                target AS (
                  SELECT m.previous_owner, (array_agg(DISTINCT m.owner))[1] AS owner
                  FROM moved m
                  WHERE NOT EXISTS (SELECT 1 FROM source_record s WHERE s.resource_id = m.previous_owner)
                  GROUP BY m.previous_owner HAVING count(DISTINCT m.owner) = 1
                )
                """;
        for (String table : List.of("issue_report", "review_item")) {
            jdbc.sql(target + "UPDATE " + table + " x SET resource_id = t.owner FROM target t WHERE x.resource_id = t.previous_owner")
                .param("from", from).param("to", to).update();
        }
    }

    /**
     * Observed resources that lost their last record (it moved to another resource) no longer exist on their own,
     * unless a user report or a review item still points at them: those cascade, so the resource is kept.
     */
    private void deleteOrphans(Collection<UUID> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        jdbc.sql("""
                DELETE FROM resource r
                WHERE r.id = ANY(:ids) AND r.data_origin = 'OBSERVED'
                  AND NOT EXISTS (SELECT 1 FROM source_record s WHERE s.resource_id = r.id)
                  AND NOT EXISTS (SELECT 1 FROM issue_report i WHERE i.resource_id = r.id)
                  AND NOT EXISTS (SELECT 1 FROM review_item v WHERE v.resource_id = r.id)
                """)
            .param("ids", candidates.toArray(UUID[]::new)).update();
    }

    /**
     * Labels are what the resource's records state now: rebuilt from source_record.extra, so a tag a source
     * dropped (and the "works with" flag derived from it) does not stay forever.
     */
    private void replaceLabels(Collection<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return;
        }
        UUID[] ids = resourceIds.toArray(UUID[]::new);
        jdbc.sql("DELETE FROM resource_label WHERE resource_id = ANY(:ids)").param("ids", ids).update();
        jdbc.sql("""
                WITH labels AS (
                  SELECT DISTINCT sr.resource_id, k.kind, btrim(left(btrim(v.value), 60)) AS value
                  FROM source_record sr
                  CROSS JOIN (VALUES ('TAG', 'tags'), ('USE_CASE', 'useCases'), ('PLATFORM', 'platforms')) AS k(kind, field)
                  CROSS JOIN LATERAL jsonb_array_elements_text(CASE WHEN jsonb_typeof(sr.extra -> k.field) = 'array'
                                                                    THEN sr.extra -> k.field ELSE '[]'::jsonb END) AS v(value)
                  WHERE sr.resource_id = ANY(:ids) AND btrim(v.value) <> ''
                ), ranked AS (
                  SELECT l.*, row_number() OVER (PARTITION BY l.resource_id, l.kind ORDER BY l.value) AS n FROM labels l
                )
                INSERT INTO resource_label (resource_id, kind, value)
                SELECT resource_id, kind, value FROM ranked WHERE kind <> 'TAG' OR n <= :maxTags
                ON CONFLICT DO NOTHING
                """)
            .param("ids", ids).param("maxTags", MAX_TAGS).update();
    }

    /** resource.event_* follow the records: earliest stated start, latest stated end (null when none states one). */
    private void refreshEventDates(Collection<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE resource r SET event_starts_at = s.starts, event_ends_at = s.ends
                FROM (SELECT resource_id, min(source_event_starts_at) AS starts, max(source_event_ends_at) AS ends
                      FROM source_record WHERE resource_id = ANY(:ids) GROUP BY resource_id) s
                WHERE r.id = s.resource_id AND r.type = 'EVENT'
                  AND (r.event_starts_at IS DISTINCT FROM s.starts OR r.event_ends_at IS DISTINCT FROM s.ends)
                """)
            .param("ids", resourceIds.toArray(UUID[]::new)).update();
    }

    /**
     * EVENTs that have not ended yet, soonest first: start (or end, when stated) on or after [today].
     * Only dates a source stated take part; an event without a stated start is never listed.
     */
    public List<UUID> upcomingEvents(Instant today, int limit) {
        return jdbc.sql("""
                SELECT id FROM resource
                WHERE type = 'EVENT' AND event_starts_at IS NOT NULL
                  AND coalesce(event_ends_at, event_starts_at) >= :today
                ORDER BY event_starts_at, title
                LIMIT :limit
                """)
            .param("today", Timestamp.from(today)).param("limit", limit)
            .query((rs, i) -> rs.getObject(1, UUID.class))
            .list();
    }

    public Map<UUID, Instant> firstDiscovered(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Instant> out = new HashMap<>();
        jdbc.sql("SELECT id, first_discovered_at FROM resource WHERE id = ANY(:ids)")
            .param("ids", ids.toArray(UUID[]::new))
            .query((rs, i) -> out.put(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()))
            .list();
        return out;
    }

    static final int MAX_VARIANTS = 4;
    /** Rows that get the full text score (stage 2 of [search]). */
    static final int PRESELECT = 300;
    /** Title similarity at which a row matches by name alone (typos, partial names). */
    static final double TITLE_SIMILARITY = 0.35;

    /**
     * Words only, for websearch_to_tsquery: user text must not become its syntax. A leading "-" means NOT,
     * quotes make phrases and a bare "or" means OR there ("-mcp server" would exclude MCP servers).
     */
    static String plainWords(String text) {
        return Arrays.stream(text.split("\\s+"))
            .map(w -> w.replace("\"", "").replaceFirst("^-+", ""))
            .filter(w -> !w.isEmpty() && !w.equalsIgnoreCase("or"))
            .collect(Collectors.joining(" "));
    }

    /** Every word of :anyq, OR-ed, in English, language-neutral and the query language's stemmer. */
    private static final String ANY_TERMS = "websearch_to_tsquery('english', h2_unaccent(:anyq))"
        + " || websearch_to_tsquery('simple', h2_unaccent(:anyq))"
        + " || websearch_to_tsquery(h2_ts_config(:lang), h2_unaccent(:anyq))";

    /**
     * Which rows the index query retrieves: any word matches (GIN resource_search_idx) or the title is
     * similar to the query. `lower(title) % :raw` is the indexable trigram operator (GIN
     * resource_title_trgm_idx; its default threshold 0.3 is below ours), rechecked against the exact
     * similarity. A plain `similarity(...) > x` cannot use an index, which turned the OR into a scan of the
     * whole table. Both arms are indexable, so PostgreSQL can combine the two indexes (BitmapOr).
     */
    static final String MATCHES = "(r.search_vector @@ (" + ANY_TERMS + ")"
        + " OR (lower(r.title) % lower(:raw) AND similarity(lower(r.title), lower(:raw)) > :similarity))";

    /**
     * The index query of [search] for [variants] required-word variants. Parameters: :anyq, :raw, :lang,
     * :origins, :preselect, :limit, :similarity and :v0..:vN. Only parameter names are concatenated into the
     * SQL, never query text. Rows are retrieved by {@link #MATCHES}.
     */
    static String searchSql(int variants) {
        StringBuilder allTerms = new StringBuilder();
        for (int i = 0; i < variants; i++) {
            if (i > 0) {
                allTerms.append(" || ");
            }
            String p = ":v" + i;
            allTerms.append("websearch_to_tsquery('english', h2_unaccent(").append(p).append(")) || ")
                .append("websearch_to_tsquery('simple', h2_unaccent(").append(p).append(")) || ")
                .append("websearch_to_tsquery(h2_ts_config(:lang), h2_unaccent(").append(p).append("))");
        }
        // Order: rows containing every word of a variant first (+2, more than the rest can add); within
        // each group, popularity (0..1, strongest metric, log-scaled per kind) plus half the text score
        // (capped at 10 -> 0..0.5). Text score alone measures word density, not relevance: for
        // "MCP server Postgres" it ranked short npm names first and crystaldba/postgres-mcp (3.4k stars)
        // 368th; with this order it is 3rd (prod catalog, 2026-10-26). Popularity is the stored column
        // kept by a trigger on source_record, not computed per matched row.
        //
        // Two stages, because ts_rank_cd per matched row is the expensive part and grows with the catalog
        // (11k resources, 2.9k matches for a 5-word query: 2.0 s in the app, close to the 3 s source
        // timeout). Stage 1 orders every match by cheap facts (every-word match, popularity, title
        // similarity) and keeps PRESELECT rows; stage 2 scores only those. Rows with every word are
        // almost always fewer than PRESELECT, so they are never cut. 2.0 s -> 0.2 s (prod, 2026-10-26).
        // The WHERE spells out any_terms instead of reading it from q: q is a CTE used twice, so it is
        // materialized, and a condition on a CTE column is a join filter that no index can serve.
        return """
            WITH q AS (
              SELECT (%1$s) AS all_terms, %2$s AS any_terms
            ), matched AS (
              SELECT r.id, (r.search_vector @@ q.all_terms) AS all_match,
                     similarity(lower(r.title), lower(:raw)) AS sim
              FROM resource r, q
              WHERE r.data_origin = ANY(:origins)
                AND %3$s
            ), pre AS (
              SELECT m.*, r.popularity AS pop
              FROM matched m JOIN resource r ON r.id = m.id
              ORDER BY m.all_match DESC, r.popularity + m.sim DESC
              LIMIT :preselect
            )
            SELECT r.*, (CASE WHEN pre.all_match THEN 2 ELSE 0 END + pre.pop
                         + 0.5 * least((ts_rank_cd(r.search_vector, q.any_terms) * 3 + pre.sim) / 10, 1)) AS score
            FROM pre JOIN resource r ON r.id = pre.id, q
            ORDER BY score DESC, r.title
            LIMIT :limit
            """.formatted(allTerms, ANY_TERMS, MATCHES);
    }

    /**
     * Full-text + trigram search over the catalog. A row is retrieved when it matches any word; it scores
     * higher when it contains every word of at least one of [requiredVariants] (the query without type
     * words, with synonyms substituted). Each variant is parsed by websearch_to_tsquery on its own and
     * the variants are OR-ed as tsqueries: "a b OR c" in websearch syntax means (a AND b) OR c, so a
     * synonym cannot be written into the query text.
     * Every variant is parsed as English, language-neutral ('simple') and, when [language] is set, with
     * that language's stemmer too (ISO code from LanguageDetector; null = English + simple only).
     */
    public List<ResourceRow> search(String query, List<String> requiredVariants, List<String> extraTerms,
                                    List<String> synonyms, String language, Set<DataOrigin> origins, int limit) {
        List<String> variants = requiredVariants.stream().map(String::trim).filter(v -> !v.isEmpty()).distinct()
            .limit(MAX_VARIANTS).toList();
        if (variants.isEmpty()) {
            variants = List.of(query);
        }
        String expansions = String.join(" ", extraTerms);
        String anyText = plainWords(String.join(" ", query, expansions, String.join(" ", synonyms)));
        String orQuery = String.join(" OR ", anyText.split(" "));
        var spec = jdbc.sql(searchSql(variants.size()))
            .param("anyq", orQuery).param("raw", query)
            .param("lang", language == null ? "simple" : language)
            .param("origins", origins.stream().map(Enum::name).toArray(String[]::new))
            .param("similarity", TITLE_SIMILARITY)
            .param("preselect", Math.max(PRESELECT, limit))
            .param("limit", limit);
        for (int i = 0; i < variants.size(); i++) {
            spec = spec.param("v" + i, plainWords(variants.get(i) + " " + expansions));
        }
        return withFacts(spec.query(this::mapResource).list());
    }

    /** Text the embedding is made from, and its hash; SQL and Java must agree, so it is defined once here. */
    private static final String EMBEDDED_TEXT = "r.title || E'\\n' || coalesce(r.description, '')";

    public record EmbeddingTask(UUID resourceId, String text, String textHash) {
    }

    /**
     * Resources without a current embedding for [model] (none yet, or title/description changed since),
     * most popular first, so a partly built index already covers what people look for. DEMO rows are skipped.
     */
    public List<EmbeddingTask> pendingEmbeddings(String model, int limit) {
        return jdbc.sql("""
                SELECT r.id, %1$s AS text, md5(%1$s) AS hash
                FROM resource r
                LEFT JOIN resource_embedding e ON e.resource_id = r.id AND e.model = :model
                WHERE r.data_origin <> 'DEMO' AND (e.resource_id IS NULL OR e.text_hash <> md5(%1$s))
                ORDER BY r.popularity DESC, r.id
                LIMIT :limit
                """.formatted(EMBEDDED_TEXT))
            .param("model", model).param("limit", limit)
            .query((rs, i) -> new EmbeddingTask(rs.getObject("id", UUID.class), rs.getString("text"), rs.getString("hash")))
            .list();
    }

    /** Stores [vectors] (same order as [tasks]) under [model], replacing older ones. */
    @Transactional
    public void saveEmbeddings(String model, List<EmbeddingTask> tasks, List<float[]> vectors) {
        for (int i = 0; i < tasks.size(); i++) {
            EmbeddingTask t = tasks.get(i);
            jdbc.sql("""
                    INSERT INTO resource_embedding (resource_id, model, text_hash, embedding, embedded_at)
                    SELECT :id, :model, :hash, CAST(:v AS vector), now()
                    WHERE EXISTS (SELECT 1 FROM resource WHERE id = :id)
                    ON CONFLICT (resource_id, model)
                    DO UPDATE SET text_hash = EXCLUDED.text_hash, embedding = EXCLUDED.embedding, embedded_at = now()
                    """)
                .param("id", t.resourceId()).param("model", model).param("hash", t.textHash())
                .param("v", vectorLiteral(vectors.get(i)))
                .update();
        }
    }

    public record EmbeddingCoverage(long embedded, long total) {
    }

    public EmbeddingCoverage embeddingCoverage(String model) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM resource_embedding WHERE model = :model) AS embedded,
                       (SELECT count(*) FROM resource WHERE data_origin <> 'DEMO') AS total
                """)
            .param("model", model)
            .query((rs, i) -> new EmbeddingCoverage(rs.getLong("embedded"), rs.getLong("total")))
            .single();
    }

    /**
     * Nearest resources to [query] by cosine distance, closest first, at most [maxDistance] away (2 = no
     * limit). Ordered by distance alone so the HNSW index serves it. The vector may be stale for a row whose text changed since; the indexer refreshes it.
     */
    public List<ResourceRow> semanticSearch(float[] query, String model, Set<DataOrigin> origins, int limit, double maxDistance) {
        return withFacts(jdbc.sql("""
                SELECT r.*, 1 - (e.embedding <=> CAST(:v AS vector)) AS score
                FROM resource_embedding e JOIN resource r ON r.id = e.resource_id
                WHERE e.model = :model AND r.data_origin = ANY(:origins)
                  AND (e.embedding <=> CAST(:v AS vector)) <= :maxDistance
                ORDER BY e.embedding <=> CAST(:v AS vector)
                LIMIT :limit
                """)
            .param("v", vectorLiteral(query)).param("model", model)
            .param("origins", origins.stream().map(Enum::name).toArray(String[]::new))
            .param("maxDistance", maxDistance).param("limit", limit)
            .query(this::mapResource)
            .list());
    }

    static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (!Float.isFinite(v[i])) {
                throw new IllegalArgumentException("embedding value is not finite");
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * Newest resources by the publication date their SOURCE states (never Helper2's discovery date), from
     * [since] up to now; [types] empty = every type. Future dates are excluded: a source clock or a
     * time-zone slip must not pin an item to the top.
     */
    public List<UUID> recentlyPublished(Instant since, Instant now, Set<String> types, Set<DataOrigin> origins, int limit) {
        return jdbc.sql("""
                SELECT id FROM resource
                WHERE published_at IS NOT NULL AND published_at >= :since AND published_at <= :now
                  AND data_origin = ANY(:origins)
                  AND (cardinality(:types) = 0 OR type = ANY(:types))
                ORDER BY published_at DESC, title
                LIMIT :limit
                """)
            .param("since", Timestamp.from(since)).param("now", Timestamp.from(now))
            .param("origins", origins.stream().map(Enum::name).toArray(String[]::new))
            .param("types", types.toArray(String[]::new))
            .param("limit", limit)
            .query((rs, i) -> rs.getObject(1, UUID.class))
            .list();
    }

    public Optional<ResourceRow> findResource(UUID id) {
        return jdbc.sql("SELECT r.*, 0 AS score FROM resource r WHERE id = :id").param("id", id)
            .query(this::mapResource).optional().map(r -> withFacts(List.of(r)).getFirst());
    }

    /**
     * The resource that now owns the record whose own URL has resource id [id]: when an npm package turns out
     * to belong to a GitHub repository, its record moves there and links to the package's id still resolve.
     * Served by source_record_identity_idx (same expression, same namespace as ResourceId).
     */
    public Optional<UUID> ownerOfRecordUrl(UUID id) {
        return jdbc.sql("""
                SELECT resource_id FROM source_record
                WHERE uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, canonical_url) = :id
                ORDER BY retrieved_at DESC LIMIT 1
                """)
            .param("id", id).query(UUID.class).optional();
    }

    public List<RecordRow> recordsOf(UUID resourceId) {
        return jdbc.sql("SELECT * FROM source_record WHERE resource_id = :id ORDER BY source_id, retrieved_at DESC")
            .param("id", resourceId)
            .query((rs, i) -> new RecordRow(
                rs.getObject("id", UUID.class), rs.getString("source_id"), rs.getString("external_id"),
                rs.getObject("resource_id", UUID.class), rs.getString("source_url"),
                rs.getTimestamp("retrieved_at").toInstant(), rs.getString("fingerprint"), toHit(rs)))
            .list();
    }

    public List<UUID> resourcesOfRecords(Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT DISTINCT resource_id FROM source_record WHERE id = ANY(:ids)")
            .param("ids", recordIds.toArray(UUID[]::new)).query(UUID.class).list();
    }

    // --- mapping ---------------------------------------------------------------------------------

    private Object[] resourceArgs(MergedResult r, DataOrigin origin) {
        DateFact pub = r.getPublished();
        DateFact upd = r.getUpdated();
        return new Object[] {
            r.getId().getValue(), r.getCanonicalUrl().getValue(), r.getUrl().toString(), clip(r.getTitle(), 300),
            clip(r.getDescription(), 4000), r.getDescriptionOrigin().name(), r.getType().name(), r.getAudience().name(),
            r.getTechLevel() == null ? null : r.getTechLevel().name(), r.getLicense(),
            r.getPricing() == null ? null : r.getPricing().name(), r.getOpenSource(),
            ts(pub.getValue()), pub.getValue() == null ? "UNKNOWN" : pub.getPrecision().name(),
            pub.getValue() == null ? null : pub.getSourceId(),
            ts(upd.getValue()), upd.getValue() == null ? null : upd.getSourceId(), origin.name(),
            languageOf(r),
        };
    }

    /** ISO code of the row's own text, so the index adds that language's stemmer; 'simple' when unclear. */
    private String languageOf(MergedResult r) {
        String text = Objects.toString(clip(r.getTitle(), 300), "") + " " + Objects.toString(clip(r.getDescription(), 1000), "");
        String code = languages.detect(text);
        return code == null ? "simple" : code;
    }

    private ResourceRow mapResource(ResultSet rs, int i) throws SQLException {
        return new ResourceRow(
            rs.getObject("id", UUID.class), rs.getString("canonical_url"), rs.getString("url"), rs.getString("title"),
            rs.getString("description"), ValueOrigin.valueOf(rs.getString("description_origin")),
            ResourceType.valueOf(rs.getString("type")), Audience.valueOf(rs.getString("audience")),
            enumOrNull(TechLevel.class, rs.getString("tech_level")), rs.getString("license"),
            enumOrNull(Pricing.class, rs.getString("pricing")), (Boolean) rs.getObject("open_source"),
            instant(rs.getTimestamp("published_at")), DatePrecision.valueOf(rs.getString("published_precision")),
            rs.getString("published_source_id"), instant(rs.getTimestamp("updated_at")), rs.getString("updated_source_id"),
            instant(rs.getTimestamp("first_discovered_at")), instant(rs.getTimestamp("last_seen_at")),
            DataOrigin.valueOf(rs.getString("data_origin")), rs.getString("enrichment_status"),
            Set.of(), Set.of(), Set.of(), List.of());
    }

    /** Labels and popularity for all [rows] in two queries (not two per row). */
    private List<ResourceRow> withFacts(List<ResourceRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        UUID[] ids = rows.stream().map(ResourceRow::id).distinct().toArray(UUID[]::new);
        Map<UUID, Map<String, Set<String>>> labels = new HashMap<>();
        jdbc.sql("SELECT resource_id, kind, value FROM resource_label WHERE resource_id = ANY(:ids) ORDER BY value")
            .param("ids", ids)
            .query((rs, i) -> labels.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                .computeIfAbsent(rs.getString(2), k -> new LinkedHashSet<>()).add(rs.getString(3)))
            .list();
        Map<UUID, List<PopularityMetric>> popularity = popularity(ids);
        return rows.stream()
            .map(r -> r.withFacts(labels.getOrDefault(r.id(), Map.of()), popularity.getOrDefault(r.id(), List.of())))
            .toList();
    }

    /**
     * The metrics the resources' records state, each still attributed to the source that reported it, so
     * index and semantic hits keep the stars a card shows and the ranking's popularity tie-break. The newest
     * value per (kind, source) wins.
     */
    private Map<UUID, List<PopularityMetric>> popularity(UUID[] ids) {
        record Row(UUID resourceId, PopularityMetric metric) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT sr.resource_id, sr.source_id, sr.retrieved_at, p->>'kind' AS kind, p->>'value' AS value, p->>'asOf' AS as_of
                FROM source_record sr, jsonb_array_elements(CASE WHEN jsonb_typeof(sr.extra -> 'popularity') = 'array'
                                                                 THEN sr.extra -> 'popularity' ELSE '[]'::jsonb END) p
                WHERE sr.resource_id = ANY(:ids)
                """)
            .param("ids", ids)
            .query((rs, i) -> {
                PopularityMetric m = metric(rs.getString("kind"), rs.getString("value"), rs.getString("source_id"),
                    rs.getString("as_of"), rs.getTimestamp("retrieved_at").toInstant());
                return new Row(rs.getObject("resource_id", UUID.class), m);
            })
            .list();
        Map<UUID, Map<String, PopularityMetric>> newest = new HashMap<>();
        for (Row row : rows) {
            PopularityMetric m = row.metric();
            if (m == null) {
                continue;
            }
            newest.computeIfAbsent(row.resourceId(), k -> new TreeMap<>())
                .merge(m.getKind() + "|" + m.getSourceId(), m, (a, b) -> a.getAsOf().isAfter(b.getAsOf()) ? a : b);
        }
        Map<UUID, List<PopularityMetric>> out = new HashMap<>();
        newest.forEach((id, byKey) -> out.put(id, byKey.values().stream()
            .sorted(Comparator.comparing(PopularityMetric::getKind)).toList()));
        return out;
    }

    private static PopularityMetric metric(String kind, String value, String sourceId, String asOf, Instant retrievedAt) {
        try {
            Instant at = asOf == null ? retrievedAt : Instant.parse(asOf);
            return new PopularityMetric(PopularityKind.valueOf(kind), Long.parseLong(value), sourceId, at);
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            return null;
        }
    }

    private ObjectNode extra(SourceHit h) {
        ObjectNode n = json.createObjectNode();
        n.put("descriptionOrigin", h.getDescriptionOrigin().name());
        n.put("audience", h.getAudience().name());
        n.put("publishedPrecision", h.getPublished().getPrecision().name());
        n.put("updatedPrecision", h.getUpdated().getPrecision().name());
        if (h.getTechLevel() != null) n.put("techLevel", h.getTechLevel().name());
        if (h.getPricing() != null) n.put("pricing", h.getPricing().name());
        if (h.getOpenSource() != null) n.put("openSource", h.getOpenSource());
        n.putPOJO("tags", h.getTags());
        n.putPOJO("useCases", h.getUseCases());
        n.putPOJO("platforms", h.getPlatforms());
        if (h.getLocation() != null) n.put("location", h.getLocation());
        if (h.getEventKind() != null) n.put("eventKind", h.getEventKind().name());
        if (h.getEventFormat() != null) n.put("eventFormat", h.getEventFormat().name());
        var pop = n.putArray("popularity");
        for (PopularityMetric m : h.getPopularity()) {
            pop.addObject().put("kind", m.getKind().name()).put("value", m.getValue()).put("asOf", m.getAsOf().toString());
        }
        return n;
    }

    private SourceHit toHit(ResultSet rs) throws SQLException {
        JsonNode x = json.readTree(rs.getString("extra"));
        String sourceId = rs.getString("source_id");
        Instant retrieved = rs.getTimestamp("retrieved_at").toInstant();
        Instant pub = instant(rs.getTimestamp("source_published_at"));
        Instant upd = instant(rs.getTimestamp("source_updated_at"));
        List<PopularityMetric> popularity = new ArrayList<>();
        x.path("popularity").forEach(p -> popularity.add(new PopularityMetric(
            PopularityKind.valueOf(p.path("kind").asString()), p.path("value").asLong(), sourceId,
            Instant.parse(p.path("asOf").asString()))));
        return new SourceHit(
            sourceId, rs.getString("external_id"), URI.create(rs.getString("source_url")),
            CanonicalUrl.of(rs.getString("canonical_url")),
            CanonicalUrl.of(rs.getString("repository_url")),
            rs.getString("title"), rs.getString("description"),
            ValueOrigin.valueOf(x.path("descriptionOrigin").asString("SOURCE_PROVIDED")),
            ResourceType.valueOf(rs.getString("type")),
            Audience.valueOf(x.path("audience").asString("UNKNOWN")),
            enumOrNull(TechLevel.class, x.path("techLevel").asString(null)),
            strings(x.path("useCases")), strings(x.path("tags")), rs.getString("license"),
            enumOrNull(Pricing.class, x.path("pricing").asString(null)), strings(x.path("platforms")),
            x.has("openSource") ? x.path("openSource").asBoolean() : null,
            date(pub, x.path("publishedPrecision").asString("INSTANT"), sourceId),
            date(upd, x.path("updatedPrecision").asString("INSTANT"), sourceId),
            popularity, retrieved, 0,
            eventDay(rs.getTimestamp("source_event_starts_at"), sourceId), eventDay(rs.getTimestamp("source_event_ends_at"), sourceId),
            x.path("location").asString(null),
            enumOrNull(EventKind.class, x.path("eventKind").asString(null)),
            enumOrNull(EventFormat.class, x.path("eventFormat").asString(null)));
    }

    private static DateFact eventDay(Timestamp t, String sourceId) {
        return t == null ? DateFact.unknown() : new DateFact(t.toInstant(), DatePrecision.DAY, sourceId, List.of(), false);
    }

    private static DateFact date(Instant value, String precision, String sourceId) {
        if (value == null) {
            return DateFact.unknown();
        }
        DatePrecision p = "DAY".equals(precision) ? DatePrecision.DAY : DatePrecision.INSTANT;
        return new DateFact(value, p, sourceId, List.of(), false);
    }

    private static Set<String> strings(JsonNode arr) {
        Set<String> out = new LinkedHashSet<>();
        arr.forEach(n -> out.add(n.asString()));
        return out;
    }

    static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String iso(Instant i) {
        return i == null ? null : i.toString();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String v) {
        return v == null ? null : Enum.valueOf(type, v);
    }

}
