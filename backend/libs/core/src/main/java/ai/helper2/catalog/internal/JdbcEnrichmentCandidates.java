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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcEnrichmentCandidates implements EnrichmentCandidates {

  private final JdbcClient jdbc;

  JdbcEnrichmentCandidates(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<UUID> pending(int limit, int minText, int maxFailed) {
    return jdbc.sql(pendingSql())
        .param("maxFailed", maxFailed)
        .param("minText", minText)
        .param("limit", limit)
        .param("oldest", oldestShare(limit))
        .withQueryTimeout(COUNT_TIMEOUT_SECONDS)
        .query(UUID.class)
        .list();
  }

  static final int OLDEST_SHARE_DIVISOR = 4;

  static int oldestShare(int limit) {
    return Math.max(1, limit / OLDEST_SHARE_DIVISOR);
  }

  private static final String NEWEST = "r.first_discovered_at DESC, r.id DESC";

  private static final String OLDEST = "r.first_discovered_at ASC, r.id ASC";

  private static final String MOST_POPULAR = "r.popularity DESC, " + NEWEST;

  private static final int OLDEST_SHARE_RANK = 2;

  private static String batchPart(int rank, String from, String order, String limit) {
    return "(SELECT r.id, r.popularity, r.first_discovered_at, "
        + rank
        + " AS part_rank FROM "
        + from
        + " ORDER BY "
        + order
        + " LIMIT "
        + limit
        + ")";
  }

  static String pendingSql() {
    return "SELECT q.id FROM ("
        + batchPart(0, PRIORITISED_FROM, NEWEST, ":limit")
        + " UNION ALL "
        + batchPart(
            1,
            "candidate_decision d JOIN resource r ON r.id = d.candidate_key"
                + " WHERE d.kind = 'RESOURCE_CANDIDATE' AND d.status = 'ACCEPTED' AND (("
                + NEW_PART
                + ") OR ("
                + REST_PART
                + "))",
            NEWEST,
            ":limit")
        + " UNION ALL "
        + batchPart(OLDEST_SHARE_RANK, "resource r WHERE " + NEW_PART, OLDEST, ":oldest")
        + " UNION ALL "
        + batchPart(3, "resource r WHERE " + NEW_PART, MOST_POPULAR, ":limit")
        + " UNION ALL "
        + batchPart(4, "resource r WHERE " + REST_PART, MOST_POPULAR, ":limit")
        + ") q GROUP BY q.id, q.popularity, q.first_discovered_at"
        + " ORDER BY min(q.part_rank),"
        + " CASE WHEN min(q.part_rank) >= "
        + OLDEST_SHARE_RANK
        + " THEN q.popularity ELSE 0 END DESC,"
        + " q.first_discovered_at DESC, q.id DESC LIMIT :limit";
  }

  static final int COUNT_TIMEOUT_SECONDS = 5;

  @Override
  public @Nullable Instant oldestPending(int minText) {
    return jdbc
        .sql("SELECT min(r.first_discovered_at) FROM resource r WHERE " + NEW_PART)
        .param("minText", minText)
        .withQueryTimeout(COUNT_TIMEOUT_SECONDS)
        .query(Timestamp.class)
        .list()
        .stream()
        .filter(Objects::nonNull)
        .map(Timestamp::toInstant)
        .findFirst()
        .orElse(null);
  }

  @Override
  public long pendingCount(int minText, int maxFailed) {
    return jdbc.sql(countSql())
        .param("maxFailed", maxFailed)
        .param("minText", minText)
        .withQueryTimeout(COUNT_TIMEOUT_SECONDS)
        .query(Long.class)
        .single();
  }

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

  private static final int AT_TOP = 0;

  private static final int NEVER_RUN = 1;
  private static final int REST = 2;
  private static final int SKIPPED_VIEW = 3;
  private static final int OUT_OF_RETRIES_VIEW = 4;

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
      query =
          query
              .param("value", after.popularity())
              .param("at", Timestamp.from(after.at()))
              .param("id", after.id());
    }
    return query.query(JdbcEnrichmentCandidates::entry).list();
  }

  private static final String BY_DISCOVERY = "r.first_discovered_at";

  private static final String BY_SKIP = "c.skipped_at";

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
                  false,
                  after != null);
          case OUT_OF_RETRIES ->
              pagePart(
                  OUT_OF_RETRIES_VIEW,
                  "resource r WHERE " + OUT_OF_RETRIES + " AND NOT " + SKIPPED,
                  BY_DISCOVERY,
                  false,
                  after != null);
        };
    return "SELECT p.id, p.title, p.enrichment_status, p.first_discovered_at, p.popularity,"
        + " p.part_rank,"
        + " ctl.skipped_at, ctl.skip_reason,"
        + " (SELECT count(*) FROM enrichment_run e WHERE e.resource_id = p.id"
        + "   AND e.outcome = 'FAILED'"
        + "   AND e.started_at > coalesce(ctl.failures_reset_at, '-infinity'))"
        + "   AS failed_runs,"
        + " last.outcome AS last_outcome, last.started_at AS last_run_at"
        + " FROM (SELECT * FROM ("
        + parts
        + ") u ORDER BY u.part_rank, u.sort_value DESC, u.sort_at DESC, u.id DESC LIMIT :size) p"
        + " LEFT JOIN enrichment_queue_control ctl ON ctl.resource_id = p.id"
        + " LEFT JOIN LATERAL (SELECT e.outcome, e.started_at FROM enrichment_run e"
        + "   WHERE e.resource_id = p.id ORDER BY e.started_at DESC LIMIT 1) last ON true"
        + " ORDER BY p.part_rank, p.sort_value DESC, p.sort_at DESC, p.id DESC";
  }

  private static String waitingParts(@Nullable BacklogCursor after) {
    Part from = after == null ? Part.PRIORITISED : after.part();
    List<String> parts = new ArrayList<>();
    if (from == Part.PRIORITISED) {
      parts.add(pagePart(AT_TOP, PRIORITISED_FROM, BY_DISCOVERY, false, after != null));
    }
    if (from != Part.REST) {
      parts.add(
          pagePart(
              NEVER_RUN,
              "resource r WHERE " + NEW_PART,
              BY_DISCOVERY,
              true,
              after != null && from == Part.NEW));
    }
    parts.add(
        pagePart(
            REST,
            "resource r WHERE " + REST_PART,
            BY_DISCOVERY,
            true,
            after != null && from == Part.REST));
    return String.join(" UNION ALL ", parts);
  }

  private static String pagePart(
      int rank, String from, String orderAt, boolean byValue, boolean afterCursor) {
    String place = orderAt + ", r.id";
    String cursor = ":at, :id";
    String order = orderAt + " DESC, r.id DESC";
    if (byValue) {
      place = "r.popularity, " + place;
      cursor = ":value, " + cursor;
      order = "r.popularity DESC, " + order;
    }
    return "(SELECT r.id, r.title, r.enrichment_status, r.first_discovered_at, r.popularity, "
        + (byValue ? "r.popularity" : "0")
        + " AS sort_value, "
        + orderAt
        + " AS sort_at, "
        + rank
        + " AS part_rank FROM "
        + from
        + (afterCursor ? " AND (" + place + ") < (" + cursor + ")" : "")
        + " ORDER BY "
        + order
        + " LIMIT :size)";
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
        rs.getBigDecimal("popularity"),
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
        resource.curated(),
        sourceStated(id));
  }

  private static final Map<String, String> STATED_FIELDS =
      Map.of("PLATFORM", "platforms", "USE_CASE", "useCase");

  private Map<String, Set<String>> sourceStated(UUID id) {
    Map<String, Set<String>> stated = new HashMap<>();
    jdbc.sql(
            "SELECT kind, value FROM resource_label WHERE resource_id = :id AND kind = ANY(:kinds)")
        .param("id", id)
        .param("kinds", STATED_FIELDS.keySet().toArray(String[]::new))
        .query((rs, i) ->
            stated
                .computeIfAbsent(STATED_FIELDS.get(rs.getString(1)), field -> new HashSet<>())
                .add(rs.getString(2)))
        .list();
    return stated;
  }

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
  public void recordArchived(UUID recordId, boolean archived, Instant checkedAt) {
    jdbc.sql(
            """
            UPDATE source_record
            SET extra = jsonb_set(extra, '{vitality}',
                CASE WHEN jsonb_exists(extra -> 'vitality', 'archived')
                          AND NOT jsonb_exists(extra -> 'vitality', 'checkedAt')
                          AND CAST(extra -> 'vitality' ->> 'statedAt' AS timestamptz)
                              > CAST(:checkedAt AS timestamptz)
                     THEN extra -> 'vitality'
                     ELSE coalesce(extra -> 'vitality', '{}'::jsonb)
                          || jsonb_build_object('archived', :archived, 'checkedAt', CAST(:checkedAt AS text))
                END)
            WHERE id = :id
            """)
        .param("archived", archived)
        .param("checkedAt", checkedAt.toString())
        .param("id", recordId)
        .update();
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
            WITH wanted AS (SELECT unnest(CAST(:titles AS text[])) AS name),
                 named AS (%s)
            SELECT wanted, (array_agg(id))[1] FROM named
            WHERE id <> :id
            GROUP BY wanted
            HAVING count(*) = 1
            """
                .formatted(TargetNames.NAMED))
        .param("id", excluding)
        .param("titles", lowerTitles.toArray(String[]::new))
        .param("byTitle", TargetNames.TYPES_BY_TITLE)
        .param("byRepositoryName", TargetNames.TYPES_BY_REPOSITORY_NAME)
        .query((rs, i) -> known.put(rs.getString(1), rs.getObject(2, UUID.class)))
        .list();
    return known;
  }
}
