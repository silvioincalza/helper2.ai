package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.enrichment.SourceOutcomes;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The Scout's last run outcome per source, written through the catalog's port on PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcSourceOutcomesIT {

  @Autowired SourceOutcomes outcomes;

  @Autowired JdbcClient jdbc;

  @BeforeEach
  void sources() {
    for (String id : List.of("aa-outcomes-1", "aa-outcomes-2", "aa-outcomes-3")) {
      jdbc.sql(
              "INSERT INTO source (id, name, mode, homepage) VALUES (:id, :id, 'LIVE', 'https://outcomes.example')")
          .param("id", id)
          .update();
    }
  }

  @AfterEach
  void clean() {
    TestcontainersConfiguration.ownerJdbc()
        .sql("DELETE FROM source WHERE id LIKE 'aa-outcomes-%'")
        .update();
  }

  @Test
  void recordsTheOutcomeAndWhenItWasChecked() {
    outcomes.record("aa-outcomes-1", "SKIPPED");

    assertThat(outcome("aa-outcomes-1")).isEqualTo("SKIPPED");
    assertThat(checkedAt("aa-outcomes-1")).isNotNull();
    assertThat(outcome("aa-outcomes-2")).isNull();
  }

  @Test
  void marksOnlyTheGivenSourcesFailed() {
    outcomes.record("aa-outcomes-3", "SUCCEEDED");

    outcomes.markFailed(List.of("aa-outcomes-1", "aa-outcomes-2"));
    outcomes.markFailed(List.of());

    assertThat(outcome("aa-outcomes-1")).isEqualTo("FAILED");
    assertThat(outcome("aa-outcomes-2")).isEqualTo("FAILED");
    assertThat(checkedAt("aa-outcomes-2")).isNotNull();
    assertThat(outcome("aa-outcomes-3")).isEqualTo("SUCCEEDED");
  }

  /** Null while the source has no outcome yet. */
  private String outcome(String id) {
    return jdbc.sql("SELECT last_outcome FROM source WHERE id = :id")
        .param("id", id)
        .query((rs, i) -> rs.getString(1))
        .list()
        .getFirst();
  }

  private Timestamp checkedAt(String id) {
    return jdbc.sql("SELECT last_checked_at FROM source WHERE id = :id")
        .param("id", id)
        .query((rs, i) -> rs.getTimestamp(1))
        .list()
        .getFirst();
  }
}
