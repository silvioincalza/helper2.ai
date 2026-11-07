package ai.helper2.catalog.internal;

import static ai.helper2.catalog.internal.BacklogRule.DUE;
import static ai.helper2.catalog.internal.BacklogRule.NEW_PART;
import static ai.helper2.catalog.internal.BacklogRule.OUT_OF_RETRIES;
import static ai.helper2.catalog.internal.BacklogRule.PRIORITISED;
import static ai.helper2.catalog.internal.BacklogRule.PRIORITISED_FROM;
import static ai.helper2.catalog.internal.BacklogRule.REST_PART;
import static ai.helper2.catalog.internal.BacklogRule.SKIPPED;

import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * enrichment_run, and the admin's decisions from the Scout's enrichment_queue_control (read only,
 * see {@link BacklogRule}).
 */
@Component
class JdbcEnrichmentCandidates implements EnrichmentCandidates {

  private final JdbcClient jdbc;

  JdbcEnrichmentCandidates(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The ones an admin moved to the top first (ADR 0026 (part was 0045) §7), then products a person
   * accepted from the review queue (ADR 0023): someone chose them, so they should not wait behind
   * the daily collector intake. Then never-run resources, then the rest, each part newest first.
   * Four parts, each with its own LIMIT: the prioritised ones are read from the few control rows,
   * the accepted ones from the few ACCEPTED review items, the other two in index order
   * (resource_enrichment_backlog_idx), so the next batch never sorts the whole table. A resource in
   * two parts counts once, in the first.
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

  /** One part of the next batch, newest first, ranked by its place in the order. */
  private static String batchPart(int rank, String from) {
    return "(SELECT r.id, r.first_discovered_at, "
        + rank
        + " AS part_rank FROM "
        + from
        + " ORDER BY r.first_discovered_at DESC, r.id DESC LIMIT :limit)";
  }

  /** The next batch of the Scout, see {@link #pending}; package-visible for the plan check. */
  static String pendingSql() {
    return "SELECT q.id FROM ("
        + batchPart(0, PRIORITISED_FROM)
        + " UNION ALL "
        + batchPart(
            1,
            "review_item i JOIN resource r ON r.canonical_url = i.candidate_url"
                + " WHERE i.kind = 'RESOURCE_CANDIDATE' AND i.status = 'ACCEPTED' AND (("
                + NEW_PART
                + ") OR ("
                + REST_PART
                + "))")
        + " UNION ALL "
        + batchPart(2, "resource r WHERE " + NEW_PART)
        + " UNION ALL "
        + batchPart(3, "resource r WHERE " + REST_PART)
        + ") q GROUP BY q.id, q.first_discovered_at"
        + " ORDER BY min(q.part_rank), q.first_discovered_at DESC, q.id DESC LIMIT :limit";
  }

  /**
   * Longest a backlog read (next batch, a queue page, the count) may run: one slow plan must not
   * hold the Scout or a page. The worst page, no row of the second part due for a retry, walks
   * every REJECTED resource: 0.4 s at 250 000 synthetic resources. The out-of-retries view walks
   * them too: 1.1 to 1.6 s with 37 500 REJECTED resources (ADR 0026 (part was 0045) §7).
   */
  static final int COUNT_TIMEOUT_SECONDS = 5;

  /**
   * The same rule as {@link #pending}, counted. It reads every resource that may wait (the backlog
   * index) and the few prioritised control rows, not the whole table; the overview caches it, and
   * the query gives up after {@link #COUNT_TIMEOUT_SECONDS}.
   */
  @Override
  public long pendingCount(int minText, int maxFailed) {
    return jdbc.sql(countSql())
        .param("maxFailed", maxFailed)
        .param("minText", minText)
        .withQueryTimeout(COUNT_TIMEOUT_SECONDS)
        .query(Long.class)
        .single();
  }

  /** The backlog count, see {@link #pendingCount}; package-visible for the measurement. */
  static String countSql() {
    return "SELECT (SELECT count(*) FROM "
        + PRIORITISED_FROM
        + ") + (SELECT count(*) FROM resource r WHERE "
        + NEW_PART
        + ") + (SELECT count(*) FROM resource r WHERE "
        + REST_PART
        + ")";
  }

  @Override
  public @Nullable Standing standing(UUID id, int minText, int maxFailed) {
    return jdbc.sql(standingSql())
        .param("id", id)
        .param("maxFailed", maxFailed)
        .param("minText", minText)
        .query((rs, i) ->
            new Standing(
                rs.getBoolean("due"),
                rs.getBoolean("out_of_retries"),
                rs.getBoolean("skipped"),
                rs.getBoolean("prioritised")))
        .optional()
        .orElse(null);
  }

  /** One resource against the rule, see {@link #standing}; package-visible for the measurement. */
  static String standingSql() {
    return "SELECT "
        + DUE
        + " AS due, ("
        + OUT_OF_RETRIES
        + ") AS out_of_retries, "
        + SKIPPED
        + " AS skipped, "
        + PRIORITISED
        + " AS prioritised FROM resource r WHERE r.id = :id";
  }

  @Override
  public int prioritisedCount(int minText, int maxFailed) {
    return jdbc.sql("SELECT count(*) FROM " + PRIORITISED_FROM)
        .param("maxFailed", maxFailed)
        .param("minText", minText)
        .query(Integer.class)
        .single();
  }

  /** A page row's rank: the part of the WAITING view, or the view it belongs to. */
  private static final int AT_TOP = 0;

  private static final int NEVER_RUN = 1;
  private static final int REST = 2;
  private static final int SKIPPED_VIEW = 3;
  private static final int OUT_OF_RETRIES_VIEW = 4;

  /**
   * A page of [view], keyset by (part, time, id): no OFFSET, so a deep page costs what the first
   * one does. Runs so far are read for the page only.
   */
  @Override
  public List<BacklogEntry> backlog(
      View view, @Nullable BacklogCursor after, int size, int minText, int maxFailed) {
    var query =
        jdbc.sql(backlogSql(view, after))
            .param("maxFailed", maxFailed)
            .param("minText", minText)
            .param("size", size)
            .withQueryTimeout(COUNT_TIMEOUT_SECONDS);
    if (after != null) {
      query = query.param("at", Timestamp.from(after.at())).param("id", after.id());
    }
    return query.query(JdbcEnrichmentCandidates::entry).list();
  }

  /** A resource part: newest discovery first. */
  private static final String BY_DISCOVERY = "r.first_discovered_at";

  /**
   * The skipped view: the latest skip first, read from the few skipped control rows (ordering by
   * discovery made the planner walk the resource table instead).
   */
  private static final String BY_SKIP = "c.skipped_at";

  /** A page of [view], see {@link #backlog}; package-visible for the plan check. */
  static String backlogSql(View view, @Nullable BacklogCursor after) {
    String parts =
        switch (view) {
          case WAITING -> waitingParts(after);
          case SKIPPED ->
              pagePart(
                  SKIPPED_VIEW,
                  "enrichment_queue_control c JOIN resource r ON r.id = c.resource_id"
                      + " WHERE c.skipped_at IS NOT NULL",
                  BY_SKIP,
                  after != null);
          case OUT_OF_RETRIES ->
              pagePart(
                  OUT_OF_RETRIES_VIEW,
                  "resource r WHERE " + OUT_OF_RETRIES + " AND NOT " + SKIPPED,
                  BY_DISCOVERY,
                  after != null);
        };
    return "SELECT p.id, p.title, p.enrichment_status, p.first_discovered_at, p.part_rank,"
        + " ctl.skipped_at, ctl.skip_reason,"
        + " (SELECT count(*) FROM enrichment_run e WHERE e.resource_id = p.id"
        + "   AND e.outcome = 'FAILED'"
        + "   AND e.started_at > coalesce(ctl.failures_reset_at, '-infinity'))"
        + "   AS failed_runs,"
        + " last.outcome AS last_outcome, last.started_at AS last_run_at"
        + " FROM (SELECT * FROM ("
        + parts
        + ") u ORDER BY u.part_rank, u.sort_at DESC, u.id DESC LIMIT :size) p"
        + " LEFT JOIN enrichment_queue_control ctl ON ctl.resource_id = p.id"
        + " LEFT JOIN LATERAL (SELECT e.outcome, e.started_at FROM enrichment_run e"
        + "   WHERE e.resource_id = p.id ORDER BY e.started_at DESC LIMIT 1) last ON true"
        + " ORDER BY p.part_rank, p.sort_at DESC, p.id DESC";
  }

  /**
   * The parts of the WAITING view from the cursor's part on: the cursor applies to its own part,
   * the parts after it start from their top.
   */
  private static String waitingParts(@Nullable BacklogCursor after) {
    Part from = after == null ? Part.PRIORITISED : after.part();
    List<String> parts = new ArrayList<>();
    if (from == Part.PRIORITISED) {
      parts.add(pagePart(AT_TOP, PRIORITISED_FROM, BY_DISCOVERY, after != null));
    }
    if (from != Part.REST) {
      parts.add(
          pagePart(
              NEVER_RUN,
              "resource r WHERE " + NEW_PART,
              BY_DISCOVERY,
              after != null && from == Part.NEW));
    }
    parts.add(
        pagePart(
            REST,
            "resource r WHERE " + REST_PART,
            BY_DISCOVERY,
            after != null && from == Part.REST));
    return String.join(" UNION ALL ", parts);
  }

  /** One part of a page, newest [orderAt] first, after the cursor when [afterCursor]. */
  private static String pagePart(int rank, String from, String orderAt, boolean afterCursor) {
    return "(SELECT r.id, r.title, r.enrichment_status, r.first_discovered_at, "
        + orderAt
        + " AS sort_at, "
        + rank
        + " AS part_rank FROM "
        + from
        + (afterCursor ? " AND (" + orderAt + ", r.id) < (:at, :id)" : "")
        + " ORDER BY "
        + orderAt
        + " DESC, r.id DESC LIMIT :size)";
  }

  private static BacklogEntry entry(ResultSet rs, int row) throws SQLException {
    int rank = rs.getInt("part_rank");
    State state =
        switch (rank) {
          case AT_TOP -> State.PRIORITISED;
          case SKIPPED_VIEW -> State.SKIPPED;
          case OUT_OF_RETRIES_VIEW -> State.OUT_OF_RETRIES;
          default -> State.WAITING;
        };
    return new BacklogEntry(
        rs.getObject("id", UUID.class),
        rs.getString("title"),
        state,
        rank <= REST ? waiting(rs.getString("enrichment_status")) : null,
        rs.getTimestamp("first_discovered_at").toInstant(),
        rs.getInt("failed_runs"),
        rs.getString("last_outcome"),
        instant(rs.getTimestamp("last_run_at")),
        instant(rs.getTimestamp("skipped_at")),
        rs.getString("skip_reason"));
  }

  private static @Nullable Instant instant(@Nullable Timestamp at) {
    return at == null ? null : at.toInstant();
  }

  private static Waiting waiting(String status) {
    return switch (status) {
      case "NOT_RUN" -> Waiting.NEW;
      case "LLM_UNAVAILABLE" -> Waiting.MODEL_UNAVAILABLE;
      default -> Waiting.RETRY;
    };
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
                      AND NOT EXISTS (SELECT 1 FROM enrichment_queue_control c
                                      WHERE c.resource_id = s.resource_id AND c.skipped_at IS NOT NULL)
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
    record Resource(
        String title, String type, @Nullable Boolean openSource, Map<String, String> curated) {}
    Resource resource =
        jdbc.sql(
                """
                SELECT title, type, open_source, curated_fields, pricing, license, tech_level, audience
                FROM resource WHERE id = :id
                """)
            .param("id", id)
            .query((rs, i) ->
                new Resource(
                    rs.getString(1),
                    rs.getString(2),
                    (Boolean) rs.getObject(3),
                    curated(
                        (String[]) rs.getArray(4).getArray(),
                        Map.of(
                            "type", rs.getString(2),
                            "openSource", String.valueOf(rs.getObject(3)),
                            "pricing", String.valueOf(rs.getString(5)),
                            "license", String.valueOf(rs.getString(6)),
                            "techLevel", String.valueOf(rs.getString(7)),
                            "audience", rs.getString(8)))))
            .single();
    String license =
        jdbc.sql(
                "SELECT license FROM source_record WHERE resource_id = :id AND license IS NOT NULL ORDER BY source_id LIMIT 1")
            .param("id", id)
            .query(String.class)
            .optional()
            .orElse(null);
    return new Candidate(
        resource.title(),
        resource.type(),
        resource.openSource(),
        license,
        sources,
        resource.curated());
  }

  /** The values of the fields an admin set (ADR 0026), from the resource's own columns. */
  private static Map<String, String> curated(String[] fields, Map<String, String> columns) {
    Map<String, String> curated = new java.util.TreeMap<>();
    for (String f : fields) {
      String v = columns.get(f);
      if (v != null && !"null".equals(v)) {
        curated.put(f, v);
      }
    }
    return Map.copyOf(curated);
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
