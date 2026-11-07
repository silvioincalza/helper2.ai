package ai.helper2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.schema.MigrateStep;
import ai.helper2.platform.schema.SchemaCheck;
import ai.helper2.platform.schema.SchemaCheck.SchemaProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migrate step of a deployment (ADR 0070), on a database of its own in the shared container:
 * {@code java -jar app.jar migrate} migrates as the owner and opens the apps' logins; the apps'
 * roles then read and write rows but change no schema and no migration history; and an app's schema
 * check waits while the database is not migrated yet and fails once the wait is over.
 */
class MigrateStepIT {

  static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.POSTGRES;
  static String url;

  /** A database of its own for each test: the step migrates it. */
  @BeforeEach
  void freshDatabase() {
    String database = "migrate_" + UUID.randomUUID().toString().replace("-", "");
    owner(POSTGRES.getJdbcUrl()).sql("CREATE DATABASE " + database).update();
    url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
  }

  @Test
  void anAppWaitsForTheMigrateStepThenTheStepMigratesAndOpensTheLogins() {
    Flyway asPublicApp = flyway(TestcontainersConfiguration.TEST_PASSWORD);

    assertThatThrownBy(() -> check(asPublicApp, Duration.ZERO, pause -> {}))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("did the migrate Job run?");

    // The app waits; meanwhile (its first pause) the migrate Job runs, and the next check passes.
    int[] pauses = {0};
    check(
        asPublicApp,
        Duration.ofMinutes(1),
        pause -> {
          pauses[0]++;
          migrateStep();
        });
    assertThat(pauses[0]).isEqualTo(1);

    for (String role : new String[] {"h2_public", "h2_backoffice"}) {
      JdbcClient app = as(role);
      assertThat(app.sql("SELECT count(*) FROM flyway_schema_history").query(Long.class).single())
          .isPositive();
      // Rows: read and write (the seeds are there), nothing more.
      assertThat(app.sql("SELECT count(*) FROM resource").query(Long.class).single()).isPositive();
      assertThat(app.sql("UPDATE resource SET id = id WHERE false").update()).isZero();
      assertDenied(app, "CREATE TABLE planted (id int)");
      assertDenied(app, "DROP TABLE resource");
      assertDenied(app, "TRUNCATE resource");
      assertDenied(app, "DELETE FROM flyway_schema_history");
      assertDenied(app, "ALTER ROLE " + role + " SUPERUSER");
    }
  }

  @Test
  void theStepRefusesToRunWithoutBothAppPasswords() {
    assertThatThrownBy(() ->
            MigrateStep.run(
                new String[] {
                  MigrateStep.COMMAND,
                  "--spring.datasource.url=" + url,
                  "--spring.datasource.username=" + POSTGRES.getUsername(),
                  "--spring.datasource.password=" + POSTGRES.getPassword(),
                  "--helper2.schema.logins.h2_public=" + TestcontainersConfiguration.TEST_PASSWORD
                }))
        .hasMessageContaining("Passwords are needed for exactly [h2_backoffice, h2_public]");
  }

  /**
   * The first rollout: the roles have no login until the Job opens it. Through the apps' pool
   * (Hikari, built as Spring Boot builds it), a refused login is waited for, not a crash.
   */
  @Test
  void anAppWaitsWhileItsLoginIsRefused() {
    DataSource pool =
        DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .url(url)
            .username("h2_public")
            .password("not-the-password")
            .build();
    Flyway asPublicApp =
        Flyway.configure().dataSource(pool).locations("classpath:db/migration").load();
    int[] pauses = {0};

    // Twice refused, then the test stops the wait: the check kept waiting instead of failing.
    assertThatThrownBy(() ->
            check(
                asPublicApp,
                Duration.ofMinutes(1),
                pause -> {
                  if (++pauses[0] == 2) {
                    throw new IllegalStateException("still waiting");
                  }
                }))
        .hasMessage("still waiting");
    // ...and once the wait is over, it says what is missing.
    assertThatThrownBy(() -> check(asPublicApp, Duration.ZERO, pause -> {}))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("a login")
        .hasMessageContaining("did the migrate Job run?");
  }

  private static void check(Flyway flyway, Duration waitFor, SchemaCheck.Sleeper sleeper) {
    SchemaCheck.awaitMigrations(
        flyway, new SchemaProperties(false, waitFor, Duration.ZERO), Clock.systemUTC(), sleeper);
  }

  private static void migrateStep() {
    try {
      MigrateStep.run(
          new String[] {
            MigrateStep.COMMAND,
            "--spring.datasource.url=" + url,
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            // The same passwords as every other test context: the roles are cluster-wide.
            "--helper2.schema.logins.h2_public=" + TestcontainersConfiguration.TEST_PASSWORD,
            "--helper2.schema.logins.h2_backoffice=" + TestcontainersConfiguration.TEST_PASSWORD
          });
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void assertDenied(JdbcClient app, String sql) {
    assertThatThrownBy(() -> app.sql(sql).update())
        .as(sql)
        .rootCause()
        .hasMessageMatching("(?s).*(permission denied|must be owner).*");
  }

  private static Flyway flyway(String password) {
    return Flyway.configure()
        .dataSource(url, "h2_public", password)
        .locations("classpath:db/migration")
        .load();
  }

  private static JdbcClient as(String role) {
    return JdbcClient.create(dataSource(url, role, TestcontainersConfiguration.TEST_PASSWORD));
  }

  private static JdbcClient owner(String jdbcUrl) {
    return JdbcClient.create(dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  private static DataSource dataSource(String jdbcUrl, String user, String password) {
    return new DriverManagerDataSource(jdbcUrl, user, password);
  }
}
