package ai.searchavo.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.TestcontainersConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PublicAppLocksIT {

  @Autowired ClusterLocks locks;

  final JdbcClient owner = TestcontainersConfiguration.ownerJdbc();

  @Test
  void theDailyJobsOfThePublicAppLockInTheirOwnTable() {
    String name = "retention-probe-" + UUID.randomUUID().toString().substring(0, 8);
    ClusterLock lock = locks.keptAlive(name, Duration.ofMinutes(1));

    boolean ran =
        lock.tryRun(() -> {
          assertThat(rows("public_app_lock", name)).isEqualTo(1);
          assertThat(rows("shedlock", name)).isZero();
        });

    assertThat(ran).isTrue();
  }

  private long rows(String table, String name) {
    return owner
        .sql("SELECT count(*) FROM " + table + " WHERE name = :name")
        .param("name", name)
        .query(Long.class)
        .single();
  }
}
