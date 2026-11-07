package ai.helper2;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * What each app's database role may do on every table (ADR 0070, V23), as the database reports it.
 * Every table of the schema must have its row in {@link #PUBLIC_APP}: a new table fails here until
 * it is classified, and a grant that differs from the row fails too. The app tests, which connect
 * as these roles, show that the rows are enough for the code.
 */
class AppRolesGrantsIT {

  static final String READ = "SELECT";
  static final Set<String> NONE = Set.of();
  static final Set<String> R = Set.of(READ);
  static final Set<String> RW = Set.of(READ, "INSERT", "UPDATE", "DELETE");
  static final Set<String> RW_NO_DELETE = Set.of(READ, "INSERT", "UPDATE");
  static final Set<String> R_INSERT_DELETE = Set.of(READ, "INSERT", "DELETE");

  /** Every privilege a table can have; the ones a row does not name must be denied. */
  static final List<String> PRIVILEGES =
      List.of(READ, "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

  /**
   * The public app's privileges per table, each with the code that needs them. The back office has
   * {@link #RW} on every table but the migration history.
   */
  static final Map<String, Set<String>> PUBLIC_APP =
      new TreeMap<>(
          Map.ofEntries(
              // ResourceStore.upsert: live results observed by a search (CatalogResultObserver).
              Map.entry("resource", RW),
              Map.entry("resource_label", RW),
              Map.entry("source_record", RW_NO_DELETE),
              // ResourceStore.registerSources at startup, JdbcSourceOutcomes.
              Map.entry("source", RW_NO_DELETE),
              // ReportIssue: the report and its review item; moved by ResourceStore.upsert.
              Map.entry("issue_report", RW_NO_DELETE),
              Map.entry("review_item", RW_NO_DELETE),
              // JdbcWebResultStore: kept per query, purged by the search retention.
              Map.entry("live_web_query_result", RW),
              // Sign-in (JdbcUsers), saved items (JdbcBookmarks), account deletion and identity
              // retention (JdbcAuditLog: append-only, so no UPDATE).
              Map.entry("app_user", RW),
              Map.entry("bookmark", R_INSERT_DELETE),
              Map.entry("admin_audit", R_INSERT_DELETE),
              // The guide's similar questions and the shared cache, and their retention.
              Map.entry("guide_similar_question", RW),
              Map.entry("ai_cache", RW),
              // ShedLock: one run at a time of the public app's jobs.
              Map.entry("shedlock", RW_NO_DELETE),
              // Read only: the catalog, its evidence, the examples, the settings.
              Map.entry("awesome_list", R),
              Map.entry("awesome_list_entry", R),
              Map.entry("catalog_marker", R),
              Map.entry("enrichment_run", R),
              Map.entry("evidence", R),
              Map.entry("field_assertion", R),
              Map.entry("field_assertion_evidence", R),
              Map.entry("home_example", R),
              Map.entry("relationship", R),
              Map.entry("relationship_evidence", R),
              Map.entry("resource_embedding", R),
              Map.entry("runtime_setting", R),
              Map.entry("summary_claim", R),
              Map.entry("summary_claim_evidence", R),
              Map.entry("flyway_schema_history", R),
              // Nothing: the Scout's and the enrichment's bookkeeping, the back office's alone.
              Map.entry("discovery_host", NONE),
              Map.entry("enrichment_queue_control", NONE),
              Map.entry("maintenance_task", NONE),
              Map.entry("scout_run", NONE),
              Map.entry("scout_schedule", NONE),
              Map.entry("source_cursor", NONE)));

  static JdbcClient owner;

  @BeforeAll
  static void connectAsOwner() {
    var postgres = TestcontainersConfiguration.POSTGRES;
    owner =
        JdbcClient.create(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
  }

  @Test
  void everyTableIsClassified() {
    assertThat(tables()).isEqualTo(PUBLIC_APP.keySet());
  }

  @Test
  void thePublicAppHasExactlyItsRow() {
    assertThat(granted("h2_public")).isEqualTo(PUBLIC_APP);
  }

  @Test
  void theBackOfficeWritesRowsEverywhereButTheHistory() {
    Map<String, Set<String>> expected = new TreeMap<>();
    for (String table : tables()) {
      expected.put(table, table.equals("flyway_schema_history") ? R : RW);
    }
    assertThat(granted("h2_backoffice")).isEqualTo(expected);
  }

  @Test
  void onlyTheBackOfficeUsesTheSequencesAndNeitherCreatesInTheSchema() {
    List<String> sequences =
        owner
            .sql("SELECT sequencename FROM pg_sequences WHERE schemaname = 'public'")
            .query(String.class)
            .list();
    assertThat(sequences).isNotEmpty();
    for (String sequence : sequences) {
      assertThat(sequencePrivilege("h2_backoffice", sequence)).as(sequence).isTrue();
      assertThat(sequencePrivilege("h2_public", sequence)).as(sequence).isFalse();
    }
    for (String role : List.of("h2_public", "h2_backoffice")) {
      assertThat(
              owner
                  .sql("SELECT has_schema_privilege(:role, 'public', 'CREATE')")
                  .param("role", role)
                  .query(Boolean.class)
                  .single())
          .as(role)
          .isFalse();
    }
  }

  @Test
  void neitherRoleIsMoreThanAnApp() {
    for (String role : List.of("h2_public", "h2_backoffice")) {
      assertThat(
              owner
                  .sql(
                      "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication"
                          + " OR rolbypassrls OR rolinherit FROM pg_roles WHERE rolname = :role")
                  .param("role", role)
                  .query(Boolean.class)
                  .single())
          .as(role + ": superuser, createdb, createrole, replication, bypassrls or inherit")
          .isFalse();
      assertThat(
              owner
                  .sql("SELECT has_database_privilege(:role, current_database(), 'TEMPORARY')")
                  .param("role", role)
                  .query(Boolean.class)
                  .single())
          .as(role + ": temporary tables")
          .isFalse();
    }
  }

  /** A later migration's new table: both apps read it, only the back office writes it. */
  @Test
  void aNewTableIsReadByBothAndWrittenByTheBackOfficeOnly() throws SQLException {
    var postgres = TestcontainersConfiguration.POSTGRES;
    try (Connection connection =
        new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .getConnection()) {
      connection.setAutoCommit(false);
      try {
        JdbcClient tx = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        tx.sql("CREATE TABLE grants_probe (id int)").update();
        assertThat(privileges(tx, "h2_public", "grants_probe")).isEqualTo(R);
        assertThat(privileges(tx, "h2_backoffice", "grants_probe")).isEqualTo(RW);
      } finally {
        connection.rollback();
      }
    }
  }

  private static Set<String> tables() {
    return new TreeSet<>(
        owner
            .sql(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")
            .query(String.class)
            .list());
  }

  private static Map<String, Set<String>> granted(String role) {
    Map<String, Set<String>> granted = new TreeMap<>();
    for (String table : tables()) {
      granted.put(table, privileges(owner, role, table));
    }
    return granted;
  }

  private static Set<String> privileges(JdbcClient jdbc, String role, String table) {
    Set<String> held = new TreeSet<>();
    for (String privilege : PRIVILEGES) {
      boolean has =
          jdbc.sql("SELECT has_table_privilege(:role, 'public.' || :table, :privilege)")
              .param("role", role)
              .param("table", table)
              .param("privilege", privilege)
              .query(Boolean.class)
              .single();
      if (has) {
        held.add(privilege);
      }
    }
    return Set.copyOf(held);
  }

  private static boolean sequencePrivilege(String role, String sequence) {
    return owner
        .sql("SELECT has_sequence_privilege(:role, 'public.' || :sequence, 'USAGE')")
        .param("role", role)
        .param("sequence", sequence)
        .query(Boolean.class)
        .single();
  }
}
