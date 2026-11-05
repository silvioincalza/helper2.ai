package ai.helper2.catalog.internal;

import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The enrichment backlog and a resource's stored source text, read from resource, source_record and
 * enrichment_run.
 */
@Component
class JdbcEnrichmentCandidates implements EnrichmentCandidates {

  private final JdbcClient jdbc;

  JdbcEnrichmentCandidates(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Products a person accepted from the review queue first (ADR 0056): someone chose them, so they
   * should not wait behind the daily collector intake. Then never-run resources, then the rest,
   * each part newest first. Three parts, each with its own LIMIT: the accepted ones are read from
   * the few ACCEPTED review items, the other two in index order (resource_enrichment_backlog_idx,
   * ADR 0045), so the next batch never sorts the whole table. A resource in two parts counts once.
   */
  @Override
  public List<UUID> pending(int limit, int minText, int maxFailed) {
    return jdbc.sql(pendingSql())
        .param("maxFailed", maxFailed)
        .param("minText", minText)
        .param("limit", limit)
        .withQueryTimeout(COUNT_TIMEOUT_SECONDS)
        .query(UUID.class)
        .list();
  }

  /** One index-ordered part of the backlog, newest first. */
  private static String part(String condition) {
    return "(SELECT r.id, r.first_discovered_at, r.enrichment_status, false AS accepted"
        + " FROM resource r WHERE "
        + condition
        + " ORDER BY r.first_discovered_at DESC, r.id DESC LIMIT :limit)";
  }

  /** The next batch of the Scout, see {@link #pending}; package-visible for the plan check. */
  static String pendingSql() {
    return "SELECT q.id FROM ("
        + "(SELECT r.id, r.first_discovered_at, r.enrichment_status, true AS accepted"
        + " FROM review_item i JOIN resource r ON r.canonical_url = i.candidate_url"
        + " WHERE i.kind = 'RESOURCE_CANDIDATE' AND i.status = 'ACCEPTED' AND (("
        + NEVER_RUN
        + ") OR ("
        + TO_RETRY
        + ")) ORDER BY r.first_discovered_at DESC, r.id DESC LIMIT :limit)"
        + " UNION ALL "
        + part(NEVER_RUN)
        + " UNION ALL "
        + part(TO_RETRY)
        + ") q GROUP BY q.id, q.first_discovered_at, q.enrichment_status"
        + " ORDER BY bool_or(q.accepted) DESC, (q.enrichment_status = 'NOT_RUN') DESC,"
        + " q.first_discovered_at DESC, q.id DESC LIMIT :limit";
  }

  /**
   * Longest a backlog read (next batch, a queue page, the count) may run: one slow plan must not
   * hold the Scout or a page. The worst page, no row of the second part due for a retry, walks
   * every REJECTED resource: 0.4 s at 250 000 synthetic resources.
   */
  static final int COUNT_TIMEOUT_SECONDS = 5;

  /**
   * The same rule as {@link #pending}, counted. It reads every resource that may wait (the backlog
   * index), not the whole table; the overview caches it, and the query gives up after {@link
   * #COUNT_TIMEOUT_SECONDS}.
   */
  @Override
  public long pendingCount(int minText, int maxFailed) {
    return jdbc.sql(
            "SELECT (SELECT count(*) FROM resource r WHERE "
                + NEVER_RUN
                + ") + (SELECT count(*) FROM resource r WHERE "
                + TO_RETRY
                + ")")
        .param("maxFailed", maxFailed)
        .param("minText", minText)
        .withQueryTimeout(COUNT_TIMEOUT_SECONDS)
        .query(Long.class)
        .single();
  }

  /**
   * A page of the backlog in {@link #pending} order, keyset by (part, first discovered, id): no
   * OFFSET, so a deep page costs what the first one does. Runs so far are read for the page only.
   */
  @Override
  public List<BacklogEntry> backlog(
      @Nullable BacklogCursor after, int size, int minText, int maxFailed) {
    boolean fromNeverRun = after == null || after.neverRun();
    String afterCursor = " AND (r.first_discovered_at, r.id) < (:at, :id)";
    String neverRun =
        "(SELECT r.id, r.title, r.enrichment_status, r.first_discovered_at FROM resource r WHERE "
            + NEVER_RUN
            + (after != null && after.neverRun() ? afterCursor : "")
            + " ORDER BY r.first_discovered_at DESC, r.id DESC LIMIT :size)";
    String toRetry =
        "(SELECT r.id, r.title, r.enrichment_status, r.first_discovered_at FROM resource r WHERE "
            + TO_RETRY
            + (after != null && !after.neverRun() ? afterCursor : "")
            + " ORDER BY r.first_discovered_at DESC, r.id DESC LIMIT :size)";
    String page =
        fromNeverRun ? neverRun + " UNION ALL " + toRetry : "SELECT * FROM " + toRetry + " t";
    var query =
        jdbc.sql(
                "SELECT p.id, p.title, p.enrichment_status, p.first_discovered_at,"
                    + " (SELECT count(*) FROM enrichment_run e WHERE e.resource_id = p.id"
                    + "   AND e.outcome = 'FAILED') AS failed_runs,"
                    + " last.outcome AS last_outcome, last.started_at AS last_run_at"
                    + " FROM ("
                    + page
                    + " LIMIT :size) p"
                    + " LEFT JOIN LATERAL (SELECT e.outcome, e.started_at FROM enrichment_run e"
                    + "   WHERE e.resource_id = p.id ORDER BY e.started_at DESC LIMIT 1) last ON true"
                    + " ORDER BY (p.enrichment_status = 'NOT_RUN') DESC, p.first_discovered_at DESC,"
                    + " p.id DESC")
            .param("maxFailed", maxFailed)
            .param("minText", minText)
            .param("size", size)
            .withQueryTimeout(COUNT_TIMEOUT_SECONDS);
    if (after != null) {
      query =
          query
              .param("at", java.sql.Timestamp.from(after.firstDiscoveredAt()))
              .param("id", after.id());
    }
    return query
        .query((rs, i) ->
            new BacklogEntry(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                switch (rs.getString("enrichment_status")) {
                  case "NOT_RUN" -> Waiting.NEW;
                  case "LLM_UNAVAILABLE" -> Waiting.MODEL_UNAVAILABLE;
                  default -> Waiting.RETRY;
                },
                rs.getTimestamp("first_discovered_at").toInstant(),
                rs.getInt("failed_runs"),
                rs.getString("last_outcome"),
                rs.getTimestamp("last_run_at") == null
                    ? null
                    : rs.getTimestamp("last_run_at").toInstant()))
        .list();
  }

  /** Resources the Scout may enrich: indexed, observed or curated, with enough source text. */
  private static final String ELIGIBLE =
      """
      r.data_origin IN ('INDEXED', 'OBSERVED', 'CURATED')
        AND EXISTS (SELECT 1 FROM source_record s
                    WHERE s.resource_id = r.id AND length(coalesce(s.description, '')) >= :minText)
      """;

  /** Never enriched: the first part of the backlog. */
  private static final String NEVER_RUN = "r.enrichment_status = 'NOT_RUN' AND " + ELIGIBLE;

  /**
   * Enriched while no model was available, or whose last run FAILED (stored as REJECTED) with fewer
   * than [maxFailed] FAILED runs: the second part.
   */
  private static final String TO_RETRY =
      "r.enrichment_status IN ('LLM_UNAVAILABLE', 'REJECTED') AND "
          + ELIGIBLE
          + """
            AND (r.enrichment_status = 'LLM_UNAVAILABLE'
                 OR ((SELECT e.outcome FROM enrichment_run e WHERE e.resource_id = r.id
                      ORDER BY e.started_at DESC LIMIT 1) = 'FAILED'
                     AND (SELECT count(*) FROM enrichment_run e
                          WHERE e.resource_id = r.id AND e.outcome = 'FAILED') < :maxFailed))
          """;

  @Override
  public List<UUID> withEnoughText(List<UUID> ids, int minText) {
    if (ids.isEmpty()) {
      return List.of();
    }
    Set<UUID> ok =
        new HashSet<>(
            jdbc.sql(
                    """
                    SELECT DISTINCT s.resource_id FROM source_record s
                    WHERE s.resource_id = ANY(:ids) AND length(coalesce(s.description, '')) >= :minText
                    """)
                .param("ids", ids.toArray(UUID[]::new))
                .param("minText", minText)
                .query(UUID.class)
                .list());
    return ids.stream().filter(ok::contains).toList();
  }

  @Override
  public @Nullable Candidate candidate(UUID id) {
    List<SourceText> sources =
        jdbc.sql(
                """
                SELECT id, source_url, retrieved_at, title || E'\\n' || coalesce(description, '') AS text,
                       source_id, external_id, repository_url
                FROM source_record WHERE resource_id = :id ORDER BY source_id
                """)
            .param("id", id)
            .query((rs, i) ->
                new SourceText(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getTimestamp(3).toInstant(),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7)))
            .list();
    if (sources.isEmpty()) {
      return null;
    }
    record Resource(String title, String type, @Nullable Boolean openSource) {}
    Resource resource =
        jdbc.sql("SELECT title, type, open_source FROM resource WHERE id = :id")
            .param("id", id)
            .query((rs, i) ->
                new Resource(rs.getString(1), rs.getString(2), (Boolean) rs.getObject(3)))
            .single();
    String license =
        jdbc.sql(
                "SELECT license FROM source_record WHERE resource_id = :id AND license IS NOT NULL ORDER BY source_id LIMIT 1")
            .param("id", id)
            .query(String.class)
            .optional()
            .orElse(null);
    return new Candidate(
        resource.title(), resource.type(), resource.openSource(), license, sources);
  }

  @Override
  public @Nullable String lastJudgedFingerprint(UUID id) {
    return jdbc.sql(
            """
            SELECT e.input_fingerprint FROM enrichment_run e JOIN resource r ON r.id = e.resource_id
            WHERE e.resource_id = :id AND e.outcome NOT IN ('LLM_UNAVAILABLE', 'FAILED')
              AND (r.enrichment_rerun_requested_at IS NULL OR e.started_at > r.enrichment_rerun_requested_at)
            ORDER BY e.started_at DESC LIMIT 1
            """)
        .param("id", id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Override
  public Map<String, UUID> titled(Collection<String> lowerTitles, UUID excluding) {
    if (lowerTitles.isEmpty()) {
      return Map.of();
    }
    Map<String, UUID> known = new HashMap<>();
    jdbc.sql(
            """
            SELECT DISTINCT ON (lower(title)) lower(title), id FROM resource
            WHERE id <> :id AND lower(title) = ANY(:titles)
            ORDER BY lower(title), first_discovered_at, id
            """)
        .param("id", excluding)
        .param("titles", lowerTitles.toArray(String[]::new))
        .query((rs, i) -> known.put(rs.getString(1), rs.getObject(2, UUID.class)))
        .list();
    return known;
  }
}
