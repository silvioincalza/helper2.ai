package ai.helper2.catalog.internal;

import ai.helper2.catalog.ReviewQueue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReviewQueue implements ReviewQueue {

  private final JdbcClient jdbc;

  JdbcReviewQueue(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public UUID open(Kind kind, UUID resourceId, String reason, String details, Links links) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, resource_id, enrichment_run_id, relationship_id, report_id, reason,
                                     details, note)
            VALUES (:id, :kind, :rid, :run, :rel, :rep, :reason, CAST(:details AS jsonb), :note)
            """)
        .param("id", id)
        .param("kind", kind.name())
        .param("rid", resourceId)
        .param("run", links.enrichmentRunId())
        .param("rel", links.relationshipId())
        .param("rep", links.reportId())
        .param("reason", reason)
        .param("details", kind.detailsAreJson() ? details : null)
        .param("note", kind.detailsAreJson() ? null : details)
        .update();
    return id;
  }

  @Override
  public void supersedeEnrichmentItems(UUID resourceId, UUID runId) {
    jdbc.sql(
            """
            UPDATE review_item SET status = 'SUPERSEDED', resolved_at = now(), resolution_note = :note
            WHERE resource_id = :rid AND status = 'OPEN' AND enrichment_run_id IS NOT NULL
              AND enrichment_run_id <> :run
              AND NOT EXISTS (SELECT 1 FROM relationship r
                              WHERE r.id = review_item.relationship_id AND r.target_origin IS NOT NULL)
            """)
        .param("rid", resourceId)
        .param("run", runId)
        .param("note", "Superseded by enrichment run " + runId)
        .update();
  }

  @Override
  public boolean proposeCandidate(Kind kind, String canonicalUrl, String reason, String details) {
    return jdbc.sql(
                "INSERT INTO review_item (id, kind, candidate_url, reason, details, note)"
                    + " SELECT :id, :kind, :url, :reason, CAST(:details AS jsonb), :note"
                    + " WHERE NOT "
                    + LISTED
                    + " AND NOT "
                    + DECIDED
                    + " ON CONFLICT (kind, candidate_url) WHERE candidate_url IS NOT NULL DO NOTHING")
            .param("id", UUID.randomUUID())
            .param("kind", kind.name())
            .param("url", canonicalUrl)
            .param("reason", reason)
            .param("details", kind.detailsAreJson() ? details : null)
            .param("note", kind.detailsAreJson() ? null : details)
            .update()
        == 1;
  }

  @Override
  public Optional<Item> openCandidate(Kind kind, String canonicalUrl) {
    return jdbc.sql(
            SELECT_ITEMS + " WHERE i.kind = :kind AND i.candidate_url = :url AND i.status = 'OPEN'")
        .param("kind", kind.name())
        .param("url", canonicalUrl)
        .query(JdbcReviewQueue::item)
        .optional();
  }

  @Override
  public boolean updateCandidate(
      UUID id, @Nullable String oldDetails, String reason, String details) {
    return jdbc.sql(
                """
                UPDATE review_item SET reason = :reason, details = CAST(:details AS jsonb)
                WHERE id = :id AND status = 'OPEN' AND candidate_url IS NOT NULL
                  AND kind IN ('RESOURCE_CANDIDATE', 'EXAMPLE_CANDIDATE')
                  AND details IS NOT DISTINCT FROM CAST(:old AS jsonb)
                """)
            .param("id", id)
            .param("old", oldDetails)
            .param("reason", reason)
            .param("details", details)
            .update()
        == 1;
  }

  @Override
  public int candidatesOpenedToday(Kind kind, String sourceId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM review_item
            WHERE kind = :kind AND candidate_url IS NOT NULL
              AND created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
              AND details ->> 'sourceId' = :source
            """)
        .param("kind", kind.name())
        .param("source", sourceId)
        .query(Integer.class)
        .single();
  }

  private static final String LISTED =
      "(EXISTS (SELECT 1 FROM resource WHERE canonical_url = :url)"
          + " OR EXISTS (SELECT 1 FROM source_record s WHERE "
          + UrlKeys.keyOf("s.canonical_url")
          + " = "
          + UrlKeys.keyOf("CAST(:url AS text)")
          + " AND s.canonical_url = :url))";

  private static final String DECIDED =
      "EXISTS (SELECT 1 FROM candidate_decision d WHERE d.kind = :kind AND d.candidate_key = "
          + UrlKeys.keyOf("CAST(:url AS text)")
          + ")";

  @Override
  public Optional<Listed> listedAs(String canonicalUrl) {
    return jdbc.sql(
            "SELECT l.id, l.title FROM ("
                + "SELECT 0 AS prio, r.id, r.title, NULL::timestamptz AS seen FROM resource r"
                + " WHERE r.canonical_url = :url"
                + " UNION ALL SELECT 1, r.id, r.title, s.retrieved_at"
                + " FROM source_record s JOIN resource r ON r.id = s.resource_id WHERE "
                + UrlKeys.keyOf("s.canonical_url")
                + " = "
                + UrlKeys.keyOf("CAST(:url AS text)")
                + " AND s.canonical_url = :url"
                + ") l ORDER BY l.prio, l.seen DESC NULLS LAST LIMIT 1")
        .param("url", canonicalUrl)
        .query((rs, i) -> new Listed(rs.getObject("id", UUID.class), rs.getString("title")))
        .optional();
  }

  @Override
  public boolean repositoryListed(String repositoryUrl) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM source_record WHERE repository_url = :url)")
        .param("url", repositoryUrl)
        .query(Boolean.class)
        .single();
  }

  @Override
  public boolean candidateKnown(Kind kind, String canonicalUrl) {
    return jdbc.sql(
            "SELECT "
                + LISTED
                + " OR EXISTS (SELECT 1 FROM review_item WHERE kind = :kind AND candidate_url = :url)"
                + " OR "
                + DECIDED)
        .param("kind", kind.name())
        .param("url", canonicalUrl)
        .query(Boolean.class)
        .single();
  }

  private static final String SELECT_ITEMS =
      """
      SELECT i.*, r.title AS resource_title,
             ARRAY(SELECT jsonb_array_elements_text(CASE
                     WHEN i.kind = 'RESOURCE_CANDIDATE' AND jsonb_typeof(i.details -> 'tags') = 'array'
                     THEN i.details -> 'tags' ELSE '[]'::jsonb END)) AS categories
      FROM review_item i LEFT JOIN resource r ON r.id = i.resource_id
      """;

  @Override
  public List<Item> openItems(Kind kind) {
    return jdbc.sql(
            SELECT_ITEMS
                + " WHERE i.status = 'OPEN' AND i.kind = :kind ORDER BY i.created_at, i.id")
        .param("kind", kind.name())
        .query(JdbcReviewQueue::item)
        .list();
  }

  @Override
  public Page list(Status status, Kind kind, int page, int size) {
    return list(status, kind, null, page, size);
  }

  @Override
  public Page list(Status status, Kind kind, String category, int page, int size) {
    String wantedStatus = status == null ? null : status.name();
    String wantedKind = kind == null ? null : kind.name();
    List<Item> items =
        jdbc.sql(
                SELECT_ITEMS
                    + " WHERE "
                    + FILTER
                    + """
                     ORDER BY i.created_at DESC, i.id
                    LIMIT :size OFFSET :offset
                    """)
            .param("status", wantedStatus)
            .param("kind", wantedKind)
            .param("category", category)
            .param("size", size)
            .param("offset", (long) page * size)
            .query(JdbcReviewQueue::item)
            .list();
    long total =
        jdbc.sql("SELECT count(*) FROM review_item i WHERE " + FILTER)
            .param("status", wantedStatus)
            .param("kind", wantedKind)
            .param("category", category)
            .query(Long.class)
            .single();
    return new Page(items, total);
  }

  private static final String FILTER =
      """
      (CAST(:status AS text) IS NULL OR i.status = :status)
        AND (CAST(:kind AS text) IS NULL OR i.kind = :kind)
        AND (CAST(:category AS text) IS NULL OR CASE
              WHEN i.kind = 'RESOURCE_CANDIDATE'
              THEN coalesce(i.details -> 'tags' @> jsonb_build_array(CAST(:category AS text)), false)
              ELSE false END)
      """;

  @Override
  public java.util.Map<Kind, Long> openCountsByKind() {
    java.util.Map<Kind, Long> counts = new java.util.EnumMap<>(Kind.class);
    for (Kind kind : Kind.values()) {
      counts.put(kind, 0L);
    }
    jdbc.sql("SELECT kind, count(*) AS n FROM review_item WHERE status = 'OPEN' GROUP BY kind")
        .query((rs, i) -> counts.put(Kind.valueOf(rs.getString("kind")), rs.getLong("n")))
        .list();
    return counts;
  }

  @Override
  public long openCount() {
    return jdbc.sql("SELECT count(*) FROM review_item WHERE status = 'OPEN'")
        .query(Long.class)
        .single();
  }

  @Override
  public void lock(UUID id) {
    jdbc.sql("SELECT 1 FROM review_item WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(Integer.class)
        .optional();
  }

  @Override
  public Optional<Item> find(UUID id) {
    return jdbc.sql(SELECT_ITEMS + " WHERE i.id = :id")
        .param("id", id)
        .query(JdbcReviewQueue::item)
        .optional();
  }

  private static Item item(ResultSet rs, int n) throws SQLException {
    return new Item(
        rs.getObject("id", UUID.class),
        Kind.valueOf(rs.getString("kind")),
        Status.valueOf(rs.getString("status")),
        rs.getObject("resource_id", UUID.class),
        rs.getString("resource_title"),
        rs.getString("reason"),
        rs.getString("details") != null ? rs.getString("details") : rs.getString("note"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
        rs.getString("resolved_by"),
        List.of((String[]) rs.getArray("categories").getArray()));
  }
}
