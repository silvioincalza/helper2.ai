package ai.helper2.catalog.internal;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetList;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcKnownTargetList implements KnownTargetList {

  private static final String KEY_OF_RELATIONSHIP =
      KnownTarget.KEY_OF_TITLE_SQL.formatted("rel.to_title");

  private static final String SELECT =
      """
      SELECT t.id, t.kind, t.name, t.added_by, t.added_at, t.origin,
             ARRAY(SELECT n.name FROM known_target_name n
                   WHERE n.target_id = t.id AND n.name_key <> %s ORDER BY n.name) AS other_names,
             (SELECT count(*) FROM relationship rel JOIN known_target_name n ON n.name_key = %s
              WHERE n.target_id = t.id AND rel.target_kind IS NOT NULL) AS relationships
      FROM known_target t
      """
          .formatted(KnownTarget.KEY_OF_TITLE_SQL.formatted("t.name"), KEY_OF_RELATIONSHIP);

  private final JdbcClient jdbc;

  JdbcKnownTargetList(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockForChange() {
    KnownTargetLock.forChange(jdbc);
  }

  @Override
  public List<Entry> all() {
    return jdbc.sql(SELECT + " ORDER BY t.kind, lower(t.name)")
        .query(JdbcKnownTargetList::entry)
        .list();
  }

  @Override
  public @Nullable Entry find(UUID id) {
    return jdbc.sql(SELECT + " WHERE t.id = :id")
        .param("id", id)
        .query(JdbcKnownTargetList::entry)
        .optional()
        .orElse(null);
  }

  private static Entry entry(ResultSet rs, int row) throws SQLException {
    return new Entry(
        rs.getObject("id", UUID.class),
        KnownTarget.Kind.valueOf(rs.getString("kind")),
        rs.getString("name"),
        Arrays.asList((String[]) rs.getArray("other_names").getArray()),
        rs.getString("added_by"),
        rs.getTimestamp("added_at").toInstant(),
        KnownTarget.Origin.valueOf(rs.getString("origin")),
        rs.getInt("relationships"));
  }

  @Override
  public Map<String, UUID> holders(Collection<String> keys) {
    Map<String, UUID> holders = new LinkedHashMap<>();
    if (keys.isEmpty()) {
      return holders;
    }
    jdbc.sql("SELECT name_key, target_id FROM known_target_name WHERE name_key = ANY(:keys)")
        .param("keys", keys.toArray(String[]::new))
        .query((rs, i) -> holders.put(rs.getString(1), rs.getObject(2, UUID.class)))
        .list();
    return holders;
  }

  @Override
  public void add(
      UUID id,
      KnownTarget.Kind kind,
      List<String> names,
      String by,
      Instant at,
      KnownTarget.Origin origin) {
    jdbc.sql(
            """
            INSERT INTO known_target (id, kind, name, added_by, added_at, origin)
            VALUES (:id, :kind, :name, :by, :at, :origin)
            """)
        .param("origin", origin.name())
        .param("id", id)
        .param("kind", kind.name())
        .param("name", names.getFirst())
        .param("by", by)
        .param("at", Timestamp.from(at))
        .update();
    insertNames(id, names);
  }

  @Override
  public void replace(UUID id, KnownTarget.Kind kind, List<String> names) {
    jdbc.sql("UPDATE known_target SET kind = :kind, name = :name WHERE id = :id")
        .param("id", id)
        .param("kind", kind.name())
        .param("name", names.getFirst())
        .update();
    jdbc.sql("DELETE FROM known_target_name WHERE target_id = :id").param("id", id).update();
    insertNames(id, names);
  }

  private void insertNames(UUID id, List<String> names) {
    for (String name : names) {
      jdbc.sql(
              "INSERT INTO known_target_name (name_key, target_id, name) VALUES (:key, :id, :name)")
          .param("key", KnownTarget.key(name))
          .param("id", id)
          .param("name", name)
          .update();
    }
  }

  @Override
  public void remove(UUID id) {
    jdbc.sql("DELETE FROM known_target WHERE id = :id").param("id", id).update();
  }

  @Override
  public int release(Collection<String> keys, String reason) {
    if (keys.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            """
            WITH released AS (
                   UPDATE relationship rel SET verification = 'UNVERIFIED', target_kind = NULL
                   WHERE rel.target_kind IS NOT NULL AND rel.to_resource_id IS NULL AND %s = ANY(:keys)
                   RETURNING rel.id, rel.from_resource_id, rel.enrichment_run_id)
            INSERT INTO review_item (id, kind, status, resource_id, enrichment_run_id, relationship_id, reason)
            SELECT gen_random_uuid(), 'RELATIONSHIP_CANDIDATE', 'OPEN', r.from_resource_id, r.enrichment_run_id,
                   r.id, :reason
            FROM released r
            """
                .formatted(KEY_OF_RELATIONSHIP))
        .param("keys", keys.toArray(String[]::new))
        .param("reason", reason)
        .update();
  }

  @Override
  public void setKind(Collection<String> keys, KnownTarget.Kind kind) {
    if (keys.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
            UPDATE relationship rel SET target_kind = :kind
            WHERE rel.target_kind IS NOT NULL AND rel.to_resource_id IS NULL AND %s = ANY(:keys)
            """
                .formatted(KEY_OF_RELATIONSHIP))
        .param("keys", keys.toArray(String[]::new))
        .param("kind", kind.name())
        .update();
  }
}
