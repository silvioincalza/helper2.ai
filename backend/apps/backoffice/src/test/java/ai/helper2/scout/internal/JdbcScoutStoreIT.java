package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.scout.RunStatus;
import ai.helper2.scout.RunSummary;
import ai.helper2.scout.ScoutStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcScoutStoreIT {

  static final Instant T0 = Instant.parse("2030-01-01T04:08:00Z");

  @Autowired ScoutStore store;
  @Autowired JdbcClient jdbc;

  final String busy = "it-busy-" + UUID.randomUUID().toString().substring(0, 8);
  final String quiet = "it-quiet-" + UUID.randomUUID().toString().substring(0, 8);

  @BeforeEach
  void sources() {
    for (String id : List.of(busy, quiet)) {
      jdbc.sql("INSERT INTO source (id, mode) VALUES (:id, 'INDEXED')").param("id", id).update();
    }
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM source WHERE id IN (:ids)").param("ids", List.of(busy, quiet)).update();
  }

  private void ran(String source, Instant at, RunStatus status) {
    store.save(new RunSummary(UUID.randomUUID(), source, status, at, at, 1, 0, 0, null));
  }

  @Test
  void eachSourceShowsItsLatestRunHoweverManyRunsOthersHave() {
    ran(quiet, T0, RunStatus.FAILED);
    for (int i = 1; i <= 60; i++) {
      ran(busy, T0.plus(i, ChronoUnit.MINUTES), RunStatus.SUCCEEDED);
    }

    List<RunSummary> latest =
        store.latestPerSource().stream()
            .filter(r -> r.getSourceId().equals(busy) || r.getSourceId().equals(quiet))
            .toList();

    assertThat(latest).extracting(RunSummary::getSourceId).containsExactly(busy, quiet);
    assertThat(latest.getFirst().getStartedAt()).isEqualTo(T0.plus(60, ChronoUnit.MINUTES));
    assertThat(latest.getLast().getStatus()).isEqualTo(RunStatus.FAILED);
  }
}
