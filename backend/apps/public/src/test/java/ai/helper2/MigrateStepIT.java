package ai.helper2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.schema.AppLogins;
import ai.helper2.platform.schema.MigrateStep;
import ai.helper2.platform.schema.SchemaCheck;
import ai.helper2.platform.schema.SchemaCheck.SchemaProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class MigrateStepIT {

  static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.POSTGRES;
  static String url;

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
  void theStepGivesEachAppItsStatementTimeout_andTheOwnerNone() {
    migrateStep();

    assertThat(statementTimeout(as("h2_public"))).isEqualTo("5s");
    assertThat(statementTimeout(as("h2_backoffice"))).isEqualTo("1min");
    assertThat(statementTimeout(owner(url))).isEqualTo("0");
    assertThatThrownBy(() -> as("h2_public").sql("SELECT pg_sleep(6)").query().singleRow())
        .rootCause()
        .hasMessageContaining("statement timeout");
  }

  @Test
  void aTimeoutTakenOutOfTheSettingsIsTakenOffTheRole() throws java.sql.SQLException {
    migrateStep();
    Map<String, String> passwords =
        Map.of(
            "h2_public", TestcontainersConfiguration.TEST_PASSWORD,
            "h2_backoffice", TestcontainersConfiguration.TEST_PASSWORD);

    AppLogins.open(
        dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()),
        passwords,
        Map.of("h2_backoffice", Duration.ofSeconds(30)));

    assertThat(statementTimeout(as("h2_public"))).isEqualTo("0");
    assertThat(statementTimeout(as("h2_backoffice"))).isEqualTo("30s");
  }

  @Test
  void aStatementTimeoutIsForAnAppRoleAndPositive() {
    DataSource owner = dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());

    assertThatThrownBy(
            () -> AppLogins.open(owner, Map.of(), Map.of("helper2", Duration.ofSeconds(5))))
        .hasMessageContaining("Statement timeouts are for [h2_backoffice, h2_public] only");
    assertThatThrownBy(() -> AppLogins.open(owner, Map.of(), Map.of("h2_public", Duration.ZERO)))
        .hasMessageContaining("must be positive");
  }

  @Test
  void aDatabaseOnAnOlderV1CatchesUpAndIsNeverRefusedAgain() {
    migrateStep();
    JdbcClient owner = owner(url);
    owner.sql("DROP INDEX resource_enrichment_value_idx").update();
    owner.sql("UPDATE flyway_schema_history SET checksum = 1 WHERE version = '1'").update();
    owner
        .sql("UPDATE flyway_schema_history SET checksum = 1 WHERE script = 'R__courses.sql'")
        .update();
    owner
        .sql(
            """
            INSERT INTO flyway_schema_history
              (installed_rank, version, description, type, script, checksum, installed_by,
               execution_time, success)
            SELECT max(installed_rank) + 1, '2', 'enrichment value index', 'SQL',
                   'V2__enrichment_value_index.sql', 2, current_user, 1, true
            FROM flyway_schema_history
            """)
        .update();

    assertThatThrownBy(MigrateStepIT::migrateStep).hasStackTraceContaining("checksum mismatch");

    migrateStep("--H2_SCHEMA_CATCH_UP=true");

    assertThat(
            owner
                .sql("SELECT to_regclass('public.resource_enrichment_value_idx') IS NOT NULL")
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(
            owner
                .sql("SELECT count(*) FROM flyway_schema_history WHERE script = 'R__courses.sql'")
                .query(Long.class)
                .single())
        .as("a changed seed runs again: the catch-up aligns versioned migrations only")
        .isEqualTo(2);
    migrateStep();
    migrateStep("--H2_SCHEMA_CATCH_UP=true");
  }

  @Test
  void aSeedTakenOutOfTheReleaseIsRefusedUntilTheCatchUpMarksItDeleted() {
    migrateStep();
    JdbcClient owner = owner(url);
    long seededRows = owner.sql("SELECT count(*) FROM resource").query(Long.class).single();
    owner
        .sql(
            """
            INSERT INTO flyway_schema_history
              (installed_rank, version, description, type, script, checksum, installed_by,
               execution_time, success)
            SELECT max(installed_rank) + 1, NULL, 'removed seed', 'SQL', 'R__removed_seed.sql', 2,
                   current_user, 1, true
            FROM flyway_schema_history
            """)
        .update();

    assertThatThrownBy(MigrateStepIT::migrateStep)
        .hasStackTraceContaining("applied migration not resolved locally: removed seed");

    migrateStep("--H2_SCHEMA_CATCH_UP=true");

    migrateStep();
    check(flyway(TestcontainersConfiguration.TEST_PASSWORD), Duration.ZERO, pause -> {});
    assertThat(owner.sql("SELECT count(*) FROM resource").query(Long.class).single())
        .as("the rows a removed seed wrote are not touched")
        .isEqualTo(seededRows);
  }

  @Test
  void theCatchUpLeavesAnEmptyDatabaseToFlyway() {
    migrateStep("--H2_SCHEMA_CATCH_UP=true");

    assertThat(owner(url).sql("SELECT count(*) FROM resource").query(Long.class).single())
        .isPositive();
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
                  "--H2_DATABASE_PUBLIC_PASSWORD=" + TestcontainersConfiguration.TEST_PASSWORD
                }))
        .hasMessageContaining("No password for h2_backoffice");
  }

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
    assertThatThrownBy(() -> check(asPublicApp, Duration.ZERO, pause -> {}))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("a login")
        .hasMessageContaining("did the migrate Job run?");
  }

  private static void check(Flyway flyway, Duration waitFor, SchemaCheck.Sleeper sleeper) {
    SchemaCheck.awaitMigrations(
        flyway, new SchemaProperties(false, waitFor, Duration.ZERO), Clock.systemUTC(), sleeper);
  }

  private static void migrateStep(String... more) {
    try {
      MigrateStep.run(
          Stream.concat(
                  Stream.of(
                      MigrateStep.COMMAND,
                      "--spring.datasource.url=" + url,
                      "--spring.datasource.username=" + POSTGRES.getUsername(),
                      "--spring.datasource.password=" + POSTGRES.getPassword(),
                      "--H2_DATABASE_PUBLIC_PASSWORD=" + TestcontainersConfiguration.TEST_PASSWORD,
                      "--H2_DATABASE_BACKOFFICE_PASSWORD="
                          + TestcontainersConfiguration.TEST_PASSWORD),
                  Stream.of(more))
              .toArray(String[]::new));
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String statementTimeout(JdbcClient client) {
    return client.sql("SHOW statement_timeout").query(String.class).single();
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
