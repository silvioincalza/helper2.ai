package ai.helper2.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Human review queue: anything that must not be shown as verified without a person deciding. Items
 * are opened here; a person decides them through the ResolveReviewItem use case.
 */
@Service
public class ReviewQueue {

  public enum Kind {
    ENRICHMENT_VALIDATION,
    RELATIONSHIP_CANDIDATE,
    USER_REPORT,
    /** A page a collector proposes for the catalog (ADR 0032 §8): no resource yet, only its URL. */
    COURSE_CANDIDATE,
    /**
     * A product page a discovery collector proposes (ADR 0051): no resource yet; details are the
     * candidate as JSON. Accepting it indexes it.
     */
    RESOURCE_CANDIDATE,
    /**
     * A home example a model drafted for a catalog resource (ADR 0049 §6): the resource is its
     * expected result, the draft is in details. Accepting it adds the example.
     */
    EXAMPLE_CANDIDATE
  }

  /**
   * SUPERSEDED: a newer enrichment run of the same resource replaced the run the item was about.
   */
  public enum Status {
    OPEN,
    ACCEPTED,
    REJECTED,
    SUPERSEDED
  }

  public record Item(
      UUID id,
      Kind kind,
      Status status,
      UUID resourceId,
      String resourceTitle,
      String reason,
      String details,
      Instant createdAt,
      Instant resolvedAt,
      String resolvedBy,
      List<String> categories) {}

  public record Links(UUID enrichmentRunId, UUID relationshipId, UUID reportId) {
    public static final Links NONE = new Links(null, null, null);
  }

  private final JdbcClient jdbc;

  public ReviewQueue(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public UUID open(Kind kind, UUID resourceId, String reason, String details, Links links) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, resource_id, enrichment_run_id, relationship_id, report_id, reason, details)
            VALUES (:id, :kind, :rid, :run, :rel, :rep, :reason, :details)
            """)
        .param("id", id)
        .param("kind", kind.name())
        .param("rid", resourceId)
        .param("run", links.enrichmentRunId())
        .param("rel", links.relationshipId())
        .param("rep", links.reportId())
        .param("reason", reason)
        .param("details", details)
        .update();
    return id;
  }

  /**
   * Closes the resource's OPEN items about earlier enrichment runs as SUPERSEDED by [runId]: a
   * person decides only what the newest run says. An item whose relationship target an admin chose
   * stays open (ADR 0064): that relationship outlives the run, so it must stay decidable.
   */
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

  /**
   * Opens a candidate item for [canonicalUrl] unless the catalog already has that resource or the
   * URL was ever proposed before (whatever was decided). Returns whether an item was opened.
   */
  public boolean proposeCandidate(Kind kind, String canonicalUrl, String reason, String details) {
    return jdbc.sql(
                """
                INSERT INTO review_item (id, kind, candidate_url, reason, details)
                SELECT :id, :kind, :url, :reason, :details
                WHERE NOT EXISTS (SELECT 1 FROM resource WHERE canonical_url = :url)
                ON CONFLICT (kind, candidate_url) WHERE candidate_url IS NOT NULL DO NOTHING
                """)
            .param("id", UUID.randomUUID())
            .param("kind", kind.name())
            .param("url", canonicalUrl)
            .param("reason", reason)
            .param("details", details)
            .update()
        == 1;
  }

  /** The OPEN candidate item of [kind] for [canonicalUrl], if one waits. */
  public Optional<Item> openCandidate(Kind kind, String canonicalUrl) {
    return jdbc.sql(
            SELECT_ITEMS + " WHERE i.kind = :kind AND i.candidate_url = :url AND i.status = 'OPEN'")
        .param("kind", kind.name())
        .param("url", canonicalUrl)
        .query(ReviewQueue::item)
        .optional();
  }

  /**
   * Replaces the reason and details of an OPEN candidate item that still holds [oldDetails]
   * (another collector found the same page, ADR 0051; an admin edited it, ADR 0065). Returns false
   * when it is no longer OPEN or someone changed it since it was read.
   */
  public boolean updateCandidate(
      UUID id, @Nullable String oldDetails, String reason, String details) {
    return jdbc.sql(
                """
                UPDATE review_item SET reason = :reason, details = :details
                WHERE id = :id AND status = 'OPEN' AND candidate_url IS NOT NULL
                  AND details IS NOT DISTINCT FROM CAST(:old AS text)
                """)
            .param("id", id)
            .param("old", oldDetails)
            .param("reason", reason)
            .param("details", details)
            .update()
        == 1;
  }

  /**
   * Candidate items of [kind] that [sourceId] opened (it found them first) since the start of the
   * current UTC day, by the database clock: for a collector's daily cap (ADR 0058).
   */
  public int candidatesOpenedToday(Kind kind, String sourceId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM review_item
            WHERE kind = :kind AND candidate_url IS NOT NULL
              AND created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
              AND details LIKE '{%' AND CAST(details AS jsonb) ->> 'sourceId' = :source
            """)
        .param("kind", kind.name())
        .param("source", sourceId)
        .query(Integer.class)
        .single();
  }

  /** True when the catalog has a resource at [canonicalUrl]. */
  public boolean inCatalog(String canonicalUrl) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE canonical_url = :url)")
        .param("url", canonicalUrl)
        .query(Boolean.class)
        .single();
  }

  /**
   * Every item with its resource's title, and the categories of a resource candidate: the tags of
   * its details (JSON; read as JSON only for that kind).
   */
  private static final String SELECT_ITEMS =
      """
      SELECT i.*, r.title AS resource_title,
             ARRAY(SELECT jsonb_array_elements_text(CASE
                     WHEN i.kind = 'RESOURCE_CANDIDATE' AND i.details LIKE '{%' THEN CASE
                       WHEN jsonb_typeof(CAST(i.details AS jsonb) -> 'tags') = 'array'
                       THEN CAST(i.details AS jsonb) -> 'tags' ELSE '[]'::jsonb END
                     ELSE '[]'::jsonb END)) AS categories
      FROM review_item i LEFT JOIN resource r ON r.id = i.resource_id
      """;

  /**
   * Every open item of [kind], oldest first. For kinds with few open items (drafts, candidates).
   */
  public List<Item> openItems(Kind kind) {
    return jdbc.sql(
            SELECT_ITEMS
                + " WHERE i.status = 'OPEN' AND i.kind = :kind ORDER BY i.created_at, i.id")
        .param("kind", kind.name())
        .query(ReviewQueue::item)
        .list();
  }

  /** One page of items: [items] in the page, [total] across every page. */
  public record Page(List<Item> items, long total) {}

  /** Newest first; all statuses when [status] is null, all kinds when [kind] is null. */
  public Page list(Status status, Kind kind, int page, int size) {
    return list(status, kind, null, page, size);
  }

  /**
   * As above; with [category], only resource candidates with that category among their tags (ADR
   * 0051).
   */
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
            .query(ReviewQueue::item)
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

  /**
   * Status, kind and category filters of [list]. A category matches a RESOURCE_CANDIDATE whose
   * details (JSON) list it in their tags; the CASE reads details as JSON only for that kind.
   */
  private static final String FILTER =
      """
      (CAST(:status AS text) IS NULL OR i.status = :status)
        AND (CAST(:kind AS text) IS NULL OR i.kind = :kind)
        AND (CAST(:category AS text) IS NULL OR CASE
              WHEN i.kind = 'RESOURCE_CANDIDATE' AND i.details LIKE '{%'
              THEN coalesce(CAST(i.details AS jsonb) -> 'tags' @> jsonb_build_array(CAST(:category AS text)), false)
              ELSE false END)
      """;

  /** Open items per kind, every kind listed (0 when none waits); for the back office overview. */
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

  /** Items waiting for a person; for the review backlog gauge. */
  public long openCount() {
    return jdbc.sql("SELECT count(*) FROM review_item WHERE status = 'OPEN'")
        .query(Long.class)
        .single();
  }

  /**
   * Locks the item's row until the current transaction ends, so an accepted item is indexed from
   * the details it is decided with: an admin's edit or a collector's merge (ADR 0065) waits, then
   * finds the item no longer open. A no-op outside a transaction.
   */
  public void lock(UUID id) {
    jdbc.sql("SELECT 1 FROM review_item WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(Integer.class)
        .optional();
  }

  public Optional<Item> find(UUID id) {
    return jdbc.sql(SELECT_ITEMS + " WHERE i.id = :id")
        .param("id", id)
        .query(ReviewQueue::item)
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
        rs.getString("details"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
        rs.getString("resolved_by"),
        List.of((String[]) rs.getArray("categories").getArray()));
  }
}
