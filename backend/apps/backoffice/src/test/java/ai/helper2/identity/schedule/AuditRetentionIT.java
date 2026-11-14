package ai.helper2.identity.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.identity.AuditLog;
import ai.helper2.identity.usecase.PurgeExpiredAuditEntries;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditRetentionIT {

  @Autowired JdbcClient jdbc;

  @Autowired AuditLog audit;

  @Autowired PurgeExpiredAuditEntries wired;

  UUID auditEntry(Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO admin_audit (id, admin_id, action, target_id, at, outcome) VALUES (:id, '1', 'START_SCOUT_RUN', NULL, :at, 'STARTED')")
        .param("id", id)
        .param("at", Timestamp.from(at))
        .update();
    return id;
  }

  long count(UUID id) {
    return jdbc.sql("SELECT count(*) FROM admin_audit WHERE id = :id")
        .param("id", id)
        .query(Long.class)
        .single();
  }

  @Test
  void entriesOlderThanTheRetentionAreDeletedAndTheOthersStay() {
    Instant now = Instant.parse("2000-07-01T00:00:00Z");
    UUID old = auditEntry(now.minus(Duration.ofDays(731)));
    UUID recent = auditEntry(now.minus(Duration.ofDays(729)));

    int deleted =
        new PurgeExpiredAuditEntries(audit, Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(730))
            .purge();

    assertThat(deleted).isEqualTo(1);
    assertThat(count(old)).isZero();
    assertThat(count(recent)).isOne();
  }

  @Test
  void theWiredPurgeKeepsTodaysEntries() {
    UUID today = auditEntry(Instant.now());

    wired.purge();

    assertThat(count(today)).isOne();
  }

  @Test
  void theBackOfficeCannotDeleteAYoungEntry() {
    UUID young = auditEntry(Instant.now().minus(Duration.ofDays(700)));

    assertThatThrownBy(
            () -> jdbc.sql("DELETE FROM admin_audit WHERE id = :id").param("id", young).update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("retention period");
    assertThat(count(young)).isOne();
  }

  @Test
  void aCutoffAheadOfTheDatabaseClockDeletesNothingYoungAndDoesNotFail() {
    UUID old = auditEntry(Instant.now().minus(Duration.ofDays(1000)));
    UUID young = auditEntry(Instant.now().minus(Duration.ofDays(700)));

    audit.deleteBefore(Instant.now().plus(Duration.ofDays(10)));

    assertThat(count(old)).isZero();
    assertThat(count(young)).isOne();
  }
}
