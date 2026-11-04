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

  /**
   * Longest the backlog count may run: the overview waits for it, and one slow plan must not hold
   * the page.
   */
  static final int COUNT_TIMEOUT_SECONDS = 5;

  /** Which resources wait for enrichment; one rule for the backlog and its count. */
  private static final String PENDING =
      """
      r.data_origin IN ('INDEXED', 'OBSERVED', 'CURATED')
        AND EXISTS (SELECT 1 FROM source_record s
                    WHERE s.resource_id = r.id AND length(coalesce(s.description, '')) >= :minText)
        AND (r.enrichment_status IN ('NOT_RUN', 'LLM_UNAVAILABLE')
             OR (r.enrichment_status = 'REJECTED'
                 AND (SELECT e.outcome FROM enrichment_run e WHERE e.resource_id = r.id
                      ORDER BY e.started_at DESC LIMIT 1) = 'FAILED'
                 AND (SELECT count(*) FROM enrichment_run e
                      WHERE e.resource_id = r.id AND e.outcome = 'FAILED') < :maxFailed))
      """;

  private final JdbcClient jdbc;

  JdbcEnrichmentCandidates(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<UUID> pending(int limit, int minText, int maxFailed) {
    return jdbc.sql(
            "SELECT r.id FROM resource r WHERE "
                + PENDING
                + """
                ORDER BY (r.enrichment_status = 'NOT_RUN') DESC, r.first_discovered_at DESC
                LIMIT :limit
                """)
        .param("maxFailed", maxFailed)
        .param("minText", minText)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /**
   * The same rule as {@link #pending}, counted. A full pass over the backlog: the overview caches
   * it, and the query gives up after {@link #COUNT_TIMEOUT_SECONDS}.
   */
  @Override
  public long pendingCount(int minText, int maxFailed) {
    return jdbc.sql("SELECT count(*) FROM resource r WHERE " + PENDING)
        .param("maxFailed", maxFailed)
        .param("minText", minText)
        .withQueryTimeout(COUNT_TIMEOUT_SECONDS)
        .query(Long.class)
        .single();
  }

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
            SELECT input_fingerprint FROM enrichment_run
            WHERE resource_id = :id AND outcome NOT IN ('LLM_UNAVAILABLE', 'FAILED')
            ORDER BY started_at DESC LIMIT 1
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
