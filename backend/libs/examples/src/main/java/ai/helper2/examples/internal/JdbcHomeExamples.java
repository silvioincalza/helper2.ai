package ai.helper2.examples.internal;

import ai.helper2.examples.CheckOutcome;
import ai.helper2.examples.CheckResult;
import ai.helper2.examples.ExampleInput;
import ai.helper2.examples.ExampleOrigin;
import ai.helper2.examples.ExampleStatus;
import ai.helper2.examples.HomeExample;
import ai.helper2.examples.HomeExamples;
import ai.helper2.shared.AudiencePath;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The home_example table (ADR 0015). The texts arrive normalised (ExampleInput). */
@Repository
class JdbcHomeExamples implements HomeExamples {

  private final JdbcClient jdbc;
  private final Clock clock;
  private final TransactionTemplate savepoint;

  JdbcHomeExamples(JdbcClient jdbc, Clock clock, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.savepoint = new TransactionTemplate(transactions);
    this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  @Override
  public List<HomeExample> served(String locale, int limit) {
    return jdbc.sql(
            """
            SELECT * FROM home_example
            WHERE locale = :locale AND status = 'ACTIVE' AND check_outcome = 'PASS'
            ORDER BY path, lower(label)
            LIMIT :limit
            """)
        .param("locale", locale)
        .param("limit", limit)
        .query(JdbcHomeExamples::example)
        .list();
  }

  @Override
  public List<HomeExample> all(@Nullable CheckOutcome outcome) {
    return jdbc.sql(
            """
            SELECT * FROM home_example
            WHERE CAST(:outcome AS text) IS NULL OR check_outcome = :outcome
            ORDER BY updated_at DESC, id
            """)
        .param("outcome", outcome == null ? null : outcome.name())
        .query(JdbcHomeExamples::example)
        .list();
  }

  @Override
  public @Nullable HomeExample find(UUID id) {
    return jdbc.sql("SELECT * FROM home_example WHERE id = :id")
        .param("id", id)
        .query(JdbcHomeExamples::example)
        .optional()
        .orElse(null);
  }

  @Override
  public Added add(
      ExampleInput input, ExampleOrigin origin, @Nullable String model, String locale) {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(clock.instant());
    // ON CONFLICT, not a caught exception: a failed insert would abort the transaction, and the
    // audit entry of the attempt is written in the same one.
    int inserted =
        jdbc.sql(
                """
                INSERT INTO home_example (id, label, query, path, locale, expected_resource_ids, origin, model,
                                          created_at, updated_at)
                VALUES (:id, :label, :query, :path, :locale, :expected, :origin, :model, :now, :now)
                ON CONFLICT DO NOTHING
                """)
            .param("id", id)
            .param("label", input.getLabel())
            .param("query", input.getQuery())
            .param("path", input.getPath().name())
            .param("locale", locale)
            .param("expected", input.getExpectedResourceIds().toArray(UUID[]::new))
            .param("origin", origin.name())
            .param("model", model)
            .param("now", now)
            .update();
    if (inserted == 0) {
      return Added.Duplicate.INSTANCE;
    }
    return new Added.Done(Objects.requireNonNull(find(id)));
  }

  @Override
  public Changed change(UUID id, ExampleInput input, ExampleStatus status, int version) {
    HomeExample current = find(id);
    if (current == null) {
      return Changed.NotFound.INSTANCE;
    }
    if (current.getVersion() != version) {
      return new Changed.Conflict(current);
    }
    boolean searchChanged =
        !current.getQuery().equals(input.getQuery())
            || current.getPath() != input.getPath()
            || !current.getExpectedResourceIds().equals(input.getExpectedResourceIds());
    Integer updated;
    try {
      // The unique indexes decide a duplicate, also between two concurrent changes. The savepoint
      // keeps the transaction usable after a rejected update: the attempt is still audited in it.
      updated = savepoint.execute(tx -> update(id, input, status, version, searchChanged));
    } catch (DuplicateKeyException e) {
      return Changed.Duplicate.INSTANCE;
    }
    if (updated == null || updated == 0) {
      HomeExample now = find(id);
      return now == null ? Changed.NotFound.INSTANCE : new Changed.Conflict(now);
    }
    return new Changed.Done(Objects.requireNonNull(find(id)));
  }

  private int update(
      UUID id, ExampleInput input, ExampleStatus status, int version, boolean searchChanged) {
    return jdbc.sql(
            """
            UPDATE home_example
            SET label = :label, query = :query, path = :path, expected_resource_ids = :expected,
                status = :status, version = version + 1, updated_at = :now,
                check_outcome = CASE WHEN :reset THEN 'PENDING' ELSE check_outcome END,
                check_best_rank = CASE WHEN :reset THEN NULL ELSE check_best_rank END,
                check_result_count = CASE WHEN :reset THEN NULL ELSE check_result_count END,
                checked_at = CASE WHEN :reset THEN NULL ELSE checked_at END
            WHERE id = :id AND version = :version
            """)
        .param("id", id)
        .param("version", version)
        .param("label", input.getLabel())
        .param("query", input.getQuery())
        .param("path", input.getPath().name())
        .param("expected", input.getExpectedResourceIds().toArray(UUID[]::new))
        .param("status", status.name())
        .param("reset", searchChanged)
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }

  @Override
  public boolean remove(UUID id) {
    return jdbc.sql("DELETE FROM home_example WHERE id = :id").param("id", id).update() > 0;
  }

  @Override
  public boolean recordCheck(HomeExample example, CheckResult result) {
    return jdbc.sql(
                """
                UPDATE home_example
                SET check_outcome = :outcome, check_best_rank = :rank, check_result_count = :count,
                    checked_at = :at
                WHERE id = :id AND query = :query AND path = :path AND expected_resource_ids = :expected
                """)
            .param("id", example.getId())
            .param("query", example.getQuery())
            .param("path", example.getPath().name())
            .param("expected", example.getExpectedResourceIds().toArray(UUID[]::new))
            .param("outcome", result.getOutcome().name())
            .param("rank", result.getBestRank())
            .param("count", result.getResultCount())
            .param(
                "at", result.getCheckedAt() == null ? null : Timestamp.from(result.getCheckedAt()))
            .update()
        > 0;
  }

  private static HomeExample example(ResultSet rs, int row) throws SQLException {
    Timestamp checkedAt = rs.getTimestamp("checked_at");
    return new HomeExample(
        rs.getObject("id", UUID.class),
        rs.getString("label"),
        rs.getString("query"),
        AudiencePath.valueOf(rs.getString("path")),
        rs.getString("locale"),
        ExampleStatus.valueOf(rs.getString("status")),
        uuids(rs.getArray("expected_resource_ids")),
        ExampleOrigin.valueOf(rs.getString("origin")),
        rs.getString("model"),
        new CheckResult(
            CheckOutcome.valueOf(rs.getString("check_outcome")),
            rs.getObject("check_best_rank", Integer.class),
            rs.getObject("check_result_count", Integer.class),
            checkedAt == null ? null : checkedAt.toInstant()),
        rs.getInt("version"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static List<UUID> uuids(Array array) throws SQLException {
    return Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).toList();
  }
}
