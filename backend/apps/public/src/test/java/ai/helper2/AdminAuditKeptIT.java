package ai.helper2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.identity.AuditLog;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminAuditKeptIT {
  @Autowired JdbcClient app;

  final JdbcClient owner = TestcontainersConfiguration.ownerJdbc();

  UUID entry(Instant at) {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            "INSERT INTO admin_audit (id, admin_id, action, target_id, at, outcome) VALUES (:id, '1', 'START_SCOUT_RUN', NULL, :at, 'STARTED')")
        .param("id", id)
        .param("at", Timestamp.from(at))
        .update();
    return id;
  }

  long count(UUID id) {
    return owner
        .sql("SELECT count(*) FROM admin_audit WHERE id = :id")
        .param("id", id)
        .query(Long.class)
        .single();
  }

  int deleteAsOwner(UUID id) {
    return owner.sql("DELETE FROM admin_audit WHERE id = :id").param("id", id).update();
  }

  @Test
  void noEntryChanges() {
    UUID id = entry(Instant.now());
    assertThatThrownBy(() ->
            owner
                .sql("UPDATE admin_audit SET outcome = 'BUSY' WHERE id = :id")
                .param("id", id)
                .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void anEntryYoungerThanTheRetentionStaysForTheOwnerToo() {
    UUID young = entry(Instant.now().minus(Duration.ofDays(728)));

    assertThatThrownBy(() -> deleteAsOwner(young))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("retention period");
    assertThat(count(young)).isOne();
  }

  @Test
  void anEntryPastTheRetentionGoes() {
    UUID old = entry(Instant.now().minus(Duration.ofDays(731)));

    assertThat(deleteAsOwner(old)).isOne();
    assertThat(count(old)).isZero();
  }

  @Test
  void theTableIsNeverTruncated() {
    assertThatThrownBy(() -> owner.sql("TRUNCATE admin_audit").update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void theTriggersHoldUnderReplicaMode() throws Exception {
    UUID young = entry(Instant.now().minus(Duration.ofDays(10)));
    var postgres = TestcontainersConfiguration.POSTGRES;
    try (Connection c =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement st = c.createStatement()) {
      st.execute("SET session_replication_role = replica");
      for (String sql :
          List.of(
              "DELETE FROM admin_audit WHERE id = '" + young + "'",
              "UPDATE admin_audit SET outcome = 'BUSY' WHERE id = '" + young + "'",
              "TRUNCATE admin_audit")) {
        assertThatThrownBy(() -> st.execute(sql)).as(sql).isInstanceOf(SQLException.class);
      }
    }
    assertThat(count(young)).isOne();
  }

  @Test
  void thePublicAppMayNeitherReadNorWriteTheTrail() {
    UUID old = entry(Instant.now().minus(Duration.ofDays(1000)));

    for (String sql :
        List.of(
            "SELECT count(*) FROM admin_audit",
            "INSERT INTO admin_audit (id, admin_id, action, outcome)"
                + " VALUES (gen_random_uuid(), '1', 'START_SCOUT_RUN', 'STARTED')",
            "DELETE FROM admin_audit WHERE id = '" + old + "'")) {
      assertThatThrownBy(() -> app.sql(sql).update())
          .as(sql)
          .isInstanceOf(DataAccessException.class)
          .rootCause()
          .hasMessageContaining("permission denied");
    }
    assertThat(count(old)).isOne();
  }

  @Test
  void theTriggersBoundIsTheShortestRetentionLessOneDay() {
    String function =
        owner
            .sql("SELECT pg_get_functiondef('h2_audit_kept'::regproc)")
            .query(String.class)
            .single();
    long days = AuditLog.SHORTEST_RETENTION.minusDays(1).toDays();

    assertThat(function).contains("interval '" + days + " days'");
  }
}
