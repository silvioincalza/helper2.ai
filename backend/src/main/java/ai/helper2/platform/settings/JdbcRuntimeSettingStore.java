package ai.helper2.platform.settings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The runtime_setting table (ADR 0058): rows are inserted and, past their retention, deleted; never
 * updated (a trigger refuses it). One change at a time across replicas: a transaction-scoped
 * advisory lock serialises the version check and the insert.
 */
class JdbcRuntimeSettingStore implements RuntimeSettingStore {

  /** The advisory lock key of every change: one change at a time, whatever the setting. */
  static final long LOCK = 0x4832_5345_5454_494EL;

  private final JdbcClient jdbc;

  JdbcRuntimeSettingStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long version() {
    return jdbc.sql("SELECT coalesce(max(version), 0) FROM runtime_setting")
        .query(Long.class)
        .single();
  }

  @Override
  public Map<String, Change> newest() {
    return jdbc
        .sql(
            """
            SELECT DISTINCT ON (name) * FROM runtime_setting ORDER BY name, version DESC
            """)
        .query(JdbcRuntimeSettingStore::change)
        .list()
        .stream()
        .collect(Collectors.toMap(Change::name, Function.identity()));
  }

  @Override
  public List<Change> history(String name, int limit) {
    return jdbc.sql(
            "SELECT * FROM runtime_setting WHERE name = :name ORDER BY version DESC LIMIT :limit")
        .param("name", name)
        .param("limit", limit)
        .query(JdbcRuntimeSettingStore::change)
        .list();
  }

  /**
   * Joins the caller's transaction (the audit row is written in the same one), or opens its own.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public @Nullable Change append(
      String name,
      @Nullable Long value,
      long expectedVersion,
      String changedBy,
      Instant at,
      @Nullable String reason) {
    jdbc.sql("SELECT pg_advisory_xact_lock(:lock)").param("lock", LOCK).query().singleRow();
    long current =
        jdbc.sql("SELECT coalesce(max(version), 0) FROM runtime_setting WHERE name = :name")
            .param("name", name)
            .query(Long.class)
            .single();
    if (current != expectedVersion) {
      return null;
    }
    return jdbc.sql(
            """
            INSERT INTO runtime_setting (name, value, changed_by, changed_at, reason)
            VALUES (:name, :value, :by, :at, :reason)
            RETURNING *
            """)
        .param("name", name)
        .param("value", value)
        .param("by", changedBy)
        .param("at", Timestamp.from(at))
        .param("reason", reason)
        .query(JdbcRuntimeSettingStore::change)
        .single();
  }

  @Override
  public int deleteBefore(Instant cutoff) {
    return jdbc.sql(
            """
            DELETE FROM runtime_setting r
            WHERE r.changed_at < :cutoff
              AND r.version < (SELECT max(n.version) FROM runtime_setting n WHERE n.name = r.name)
            """)
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }

  private static Change change(ResultSet rs, int row) throws SQLException {
    Long value = rs.getObject("value", Long.class);
    return new Change(
        rs.getLong("version"),
        rs.getString("name"),
        value,
        rs.getString("changed_by"),
        rs.getTimestamp("changed_at").toInstant(),
        rs.getString("reason"));
  }
}
