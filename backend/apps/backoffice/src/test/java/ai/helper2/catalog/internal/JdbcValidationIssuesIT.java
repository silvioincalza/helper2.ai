package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.enrichment.ValidationIssues;
import ai.helper2.catalog.enrichment.ValidationIssues.Count;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcValidationIssuesIT {

  static final Instant SINCE = Instant.parse("2031-03-01T00:00:00Z");

  @Autowired ValidationIssues issues;
  @Autowired JdbcClient jdbc;

  final UUID resource = UUID.randomUUID();

  @BeforeEach
  void insertResource() {
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'Issues IT', 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", resource)
        .param("url", "https://validation-issues.example/" + resource)
        .update();
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", resource).update();
  }

  private void run(Instant at, String... errors) {
    jdbc.sql(
            """
            INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint,
                                        started_at, outcome, validation_errors)
            VALUES (:id, :r, 'p', 'm', 'v', :fp, :at, 'PARTIAL', CAST(:errors AS jsonb))
            """)
        .param("id", UUID.randomUUID())
        .param("r", resource)
        .param("fp", UUID.randomUUID().toString())
        .param("at", Timestamp.from(at))
        .param("errors", new ObjectMapper().writeValueAsString(List.of(errors)))
        .update();
  }

  @Test
  void issuesAreCountedPerRunBySubjectAndRuleWithoutQuotesAndNumbers() {
    run(
        SINCE.plusSeconds(60),
        "field pricing: the cited excerpt does not state the value",
        "field platforms: excerpt length 16 outside 20..300",
        "relationship INTEGRATES_WITH legislation.gov.au: excerpt not found in cited evidence (readme): \"An MCP\"");
    run(
        SINCE.plusSeconds(120),
        "field pricing: the cited excerpt does not state the value",
        "field platforms: excerpt length 9 outside 20..300",
        "summary claim: cites text that may not support a claim");
    run(SINCE.minusSeconds(60), "field pricing: the cited excerpt does not state the value");

    ValidationIssues.Summary summary = issues.since(SINCE, 10);

    assertThat(summary.runs()).isEqualTo(2);
    assertThat(summary.top())
        .containsExactly(
            new Count("field platforms", "excerpt length N outside N..N", 2),
            new Count("field pricing", "the cited excerpt does not state the value", 2),
            new Count("relationship INTEGRATES_WITH", "excerpt not found in cited evidence", 1),
            new Count("summary claim", "cites text that may not support a claim", 1));
    assertThat(issues.since(SINCE, 1).top()).hasSize(1);
  }
}
