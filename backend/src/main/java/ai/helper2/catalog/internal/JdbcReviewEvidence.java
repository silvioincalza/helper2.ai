package ai.helper2.catalog.internal;

import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The evidence of a review item from the catalog tables. At most three statements whatever the
 * number of fields: the item's links with its run, then the fields and the quotes of all of them,
 * or for a relationship candidate the relationship and its quotes.
 */
@Repository
class JdbcReviewEvidence implements ReviewEvidence {

  private final JdbcClient jdbc;

  JdbcReviewEvidence(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Evidence of(UUID itemId) {
    Linked linked =
        jdbc.sql(
                """
                SELECT i.relationship_id, r.id, r.provider, r.model, r.prompt_version, r.outcome,
                       r.started_at, r.finished_at, r.extra_evidence,
                       ARRAY(SELECT jsonb_array_elements_text(r.validation_errors) LIMIT :max) AS errors
                FROM review_item i LEFT JOIN enrichment_run r ON r.id = i.enrichment_run_id
                WHERE i.id = :id
                """)
            .param("id", itemId)
            .param("max", MAX_FIELDS)
            .query(JdbcReviewEvidence::linked)
            .optional()
            .orElse(Linked.NOTHING);
    if (linked.relationshipId() != null) {
      return new Evidence(
          linked.run(), List.of(), List.of(), relationship(linked.relationshipId()));
    }
    if (linked.run() == null) {
      return Evidence.NONE;
    }
    return new Evidence(linked.run(), linked.errors(), fields(linked.run().id()), null);
  }

  private record Linked(@Nullable UUID relationshipId, @Nullable Run run, List<String> errors) {
    static final Linked NOTHING = new Linked(null, null, List.of());
  }

  private static Linked linked(ResultSet rs, int n) throws SQLException {
    UUID runId = rs.getObject("id", UUID.class);
    Run run =
        runId == null
            ? null
            : new Run(
                runId,
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("outcome"),
                rs.getTimestamp("started_at").toInstant(),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("extra_evidence"));
    List<String> errors =
        runId == null ? List.of() : List.of((String[]) rs.getArray("errors").getArray());
    return new Linked(rs.getObject("relationship_id", UUID.class), run, errors);
  }

  /** The run's fields, CONFLICTING first, each with its quotes. */
  private List<Field> fields(UUID runId) {
    Map<UUID, Field> fields = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT id, field, value, origin, verification FROM field_assertion
            WHERE enrichment_run_id = :run
            ORDER BY CASE verification WHEN 'CONFLICTING' THEN 0 WHEN 'UNVERIFIED' THEN 1
                                       WHEN 'VERIFIED' THEN 2 ELSE 3 END, field, id
            LIMIT :max
            """)
        .param("run", runId)
        .param("max", MAX_FIELDS)
        .query(rs -> {
          fields.put(
              rs.getObject("id", UUID.class),
              new Field(
                  rs.getString("field"),
                  rs.getString("value"),
                  ValueOrigin.valueOf(rs.getString("origin")),
                  Verification.valueOf(rs.getString("verification")),
                  List.of()));
        });
    if (fields.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<Quote>> quotes =
        quotes("field_assertion_evidence", "assertion_id", List.copyOf(fields.keySet()));
    return fields.entrySet().stream()
        .map(e -> withQuotes(e.getValue(), quotes.getOrDefault(e.getKey(), List.of())))
        .toList();
  }

  private static Field withQuotes(Field f, List<Quote> quotes) {
    return new Field(f.field(), f.value(), f.origin(), f.verification(), quotes);
  }

  private @Nullable Relationship relationship(UUID id) {
    List<Quote> quotes =
        quotes("relationship_evidence", "relationship_id", List.of(id)).getOrDefault(id, List.of());
    return jdbc.sql(
            "SELECT kind, to_title, to_resource_id, verification, origin FROM relationship WHERE id = :id")
        .param("id", id)
        .query((rs, n) ->
            new Relationship(
                rs.getString("kind"),
                rs.getString("to_title"),
                rs.getObject("to_resource_id", UUID.class),
                Verification.valueOf(rs.getString("verification")),
                ValueOrigin.valueOf(rs.getString("origin")),
                quotes))
        .optional()
        .orElse(null);
  }

  /**
   * The newest {@link #MAX_QUOTES} quotes of each owner, in one statement. [link] and [owner] are
   * this class's own constants, never input.
   */
  private Map<UUID, List<Quote>> quotes(String link, String owner, List<UUID> owners) {
    Map<UUID, List<Quote>> quotes = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT owner, excerpt, source_url, source_id, retrieved_at FROM (
              SELECT l.%2$s AS owner, e.excerpt, e.source_url, s.source_id, e.retrieved_at,
                     row_number() OVER (PARTITION BY l.%2$s ORDER BY e.retrieved_at DESC, e.id) AS n
              FROM %1$s l JOIN evidence e ON e.id = l.evidence_id
                          LEFT JOIN source_record s ON s.id = e.source_record_id
              WHERE l.%2$s IN (:owners)) q
            WHERE n <= :max ORDER BY owner, n
            """
                .formatted(link, owner))
        .param("owners", owners)
        .param("max", MAX_QUOTES)
        .query(rs -> {
          quotes
              .computeIfAbsent(rs.getObject("owner", UUID.class), k -> new ArrayList<>())
              .add(
                  new Quote(
                      rs.getString("excerpt"),
                      rs.getString("source_url"),
                      rs.getString("source_id"),
                      rs.getTimestamp("retrieved_at").toInstant()));
        });
    return quotes;
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
