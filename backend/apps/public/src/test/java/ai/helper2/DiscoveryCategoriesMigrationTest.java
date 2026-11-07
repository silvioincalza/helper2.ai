package ai.helper2;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.BackendSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V9 (ADR 0022): the records and open candidates of the old single tavily-discovery collector move
 * to the collector of their first category. The schema is migrated already, so the test puts rows
 * of the old collector back and runs the migration's script again on them.
 */
class DiscoveryCategoriesMigrationTest {

  static final Path V9 = BackendSources.migration("V9__discovery_categories.sql");

  static DriverManagerDataSource dataSource;
  static JdbcClient jdbc;

  @BeforeAll
  static void migrate() {
    var postgres = TestcontainersConfiguration.POSTGRES;
    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = JdbcClient.create(dataSource);
  }

  @Test
  void theOldCollectorsRowsMoveToTheCollectorOfTheirFirstCategory() throws Exception {
    String run = UUID.randomUUID().toString().substring(0, 8);
    jdbc.sql(
            """
            INSERT INTO source (id, name, mode, homepage) VALUES ('tavily-discovery', 'Old', 'INDEXED', 'https://tavily.com')
            ON CONFLICT (id) DO NOTHING
            """)
        .update();
    UUID tagged = record(run, "tagged", "[\"not-a-category\", \"local-ai\", \"self-hosted\"]");
    UUID untagged = record(run, "untagged", "[]");
    UUID item = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, candidate_url, reason, details)
            VALUES (:id, 'RESOURCE_CANDIDATE', :url, 'New PRODUCT', :details)
            """)
        .param("id", item)
        .param("url", "https://v9-" + run + ".example/candidate")
        .param(
            "details",
            "{\"sourceId\":\"tavily-discovery\",\"url\":\"https://x\",\"tags\":[\"coding\"]}")
        .update();

    runV9();

    assertThat(sourceOf(tagged)).isEqualTo("discovery-local-ai");
    assertThat(categoriesOf(tagged)).isEqualTo("[\"local-ai\", \"self-hosted\"]");
    assertThat(sourceOf(untagged)).isEqualTo("discovery-ai-assistants");
    assertThat(
            jdbc.sql("SELECT CAST(details AS jsonb) ->> 'sourceId' FROM review_item WHERE id = :id")
                .param("id", item)
                .query(String.class)
                .single())
        .isEqualTo("discovery-coding");
    assertThat(
            jdbc.sql("SELECT count(*) FROM source WHERE id = 'tavily-discovery'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void aResourceWhoseDiscoveryRecordIsGoneMovesToTheFallbackCollector() throws Exception {
    String run = UUID.randomUUID().toString().substring(0, 8);
    jdbc.sql(
            """
            INSERT INTO source (id, name, mode, homepage) VALUES ('tavily-discovery', 'Old', 'INDEXED', 'https://tavily.com')
            ON CONFLICT (id) DO NOTHING
            """)
        .update();
    UUID kept = resource("https://v9-" + run + ".example/kept");
    jdbc.sql("UPDATE resource SET updated_source_id = 'tavily-discovery' WHERE id = :id")
        .param("id", kept)
        .update();

    runV9();

    assertThat(
            jdbc.sql(
                    "SELECT published_source_id || ' ' || updated_source_id FROM resource WHERE id = :id")
                .param("id", kept)
                .query(String.class)
                .single())
        .isEqualTo("discovery-ai-assistants discovery-ai-assistants");
    assertThat(
            jdbc.sql("SELECT count(*) FROM source WHERE id = 'tavily-discovery'")
                .query(Long.class)
                .single())
        .isZero();
  }

  private static void runV9() throws Exception {
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute(Files.readString(V9));
      c.commit();
    }
  }

  private static UUID resource(String url) {
    UUID resource = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin,
                                  published_source_id)
            VALUES (:id, :url, :url, 'V9', 'SOURCE_PROVIDED', 'ARTICLE', 'BOTH', 'INDEXED', 'tavily-discovery')
            """)
        .param("id", resource)
        .param("url", url)
        .update();
    return resource;
  }

  private static UUID record(String run, String name, String tags) {
    String url = "https://v9-" + run + ".example/" + name;
    UUID resource = resource(url);
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, type,
                                       retrieved_at, fingerprint, extra)
            VALUES (:id, 'tavily-discovery', :url, :rid, :url, :url, 'V9', 'ARTICLE', now(), 'f',
                    CAST(:extra AS jsonb))
            """)
        .param("id", id)
        .param("url", url)
        .param("rid", resource)
        .param("extra", "{\"tags\": " + tags + "}")
        .update();
    return id;
  }

  private static String sourceOf(UUID record) {
    return jdbc.sql("SELECT source_id FROM source_record WHERE id = :id")
        .param("id", record)
        .query(String.class)
        .single();
  }

  private static String categoriesOf(UUID record) {
    return jdbc.sql("SELECT CAST(extra -> 'categories' AS text) FROM source_record WHERE id = :id")
        .param("id", record)
        .query(String.class)
        .single();
  }
}
