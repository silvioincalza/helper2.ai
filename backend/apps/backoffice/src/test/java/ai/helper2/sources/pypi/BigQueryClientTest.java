package ai.helper2.sources.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.search.SourceFailure;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class BigQueryClientTest {

  static final String SCHEMA =
      """
      "schema": {"fields": [
        {"name": "name", "type": "STRING", "mode": "NULLABLE"},
        {"name": "classifiers", "type": "STRING", "mode": "REPEATED"},
        {"name": "upload_time", "type": "TIMESTAMP", "mode": "NULLABLE"}]}
      """;

  static final String ONE_ROW =
      """
      {"kind": "bigquery#queryResponse", "jobComplete": true, %s,
       "jobReference": {"projectId": "helper2ai", "jobId": "job-1", "location": "US"},
       "totalRows": "1",
       "rows": [{"f": [{"v": "torch-tools"},
                       {"v": [{"v": "Topic :: Scientific/Engineering :: Artificial Intelligence"}]},
                       {"v": "1762689600000000"}]}]}
      """
          .formatted(SCHEMA);

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  private BigQueryClient client() {
    server.route(
        "/token", FixtureServer.Reply.ok("{\"access_token\":\"ya29.t\",\"expires_in\":3600}"));
    RestClient http =
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).build();
    GoogleAccessTokens tokens =
        new GoogleAccessTokens(
            GoogleServiceAccount.fromBase64Json(
                GoogleKeys.base64(GoogleKeys.keyJson(GoogleServiceAccount.TOKEN_URI.toString()))),
            http,
            SourceFixtures.CLOCK,
            URI.create(server.baseUrl() + "/token"));
    return new BigQueryClient(
        http,
        tokens,
        "helper2ai",
        SourceFixtures.CLOCK,
        URI.create(server.baseUrl() + "/bigquery/v2"),
        5_000_000_000L);
  }

  @Test
  void aQueryIsSentWithItsParametersAndACostCapAndItsRowsAreRead() {
    server.route("/bigquery/v2/projects/helper2ai/queries", FixtureServer.Reply.ok(ONE_ROW));

    List<Map<String, Object>> rows =
        client()
            .query(
                "SELECT 1 WHERE upload_time >= @since",
                List.of(new BigQueryClient.Parameter("since", "TIMESTAMP", "2026-11-01T00:00:00Z")),
                100);

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("name", "torch-tools")
        .containsEntry(
            "classifiers", List.of("Topic :: Scientific/Engineering :: Artificial Intelligence"))
        .containsEntry("upload_time", "1762689600000000");
    int query = server.requests.indexOf("/bigquery/v2/projects/helper2ai/queries");
    JsonNode body = new ObjectMapper().readTree(server.requestBodies.get(query));
    assertThat(body.path("query").asString()).isEqualTo("SELECT 1 WHERE upload_time >= @since");
    assertThat(body.path("useLegacySql").asBoolean(true)).isFalse();
    assertThat(body.path("parameterMode").asString()).isEqualTo("NAMED");
    assertThat(body.path("maximumBytesBilled").asString()).isEqualTo("5000000000");
    assertThat(body.path("formatOptions").path("useInt64Timestamp").asBoolean()).isTrue();
    assertThat(body.path("queryParameters").get(0).path("parameterValue").path("value").asString())
        .isEqualTo("2026-11-01T00:00:00Z");
    assertThat(server.requestHeaders.get(query).get("Authorization"))
        .containsExactly("Bearer ya29.t");
  }

  @Test
  void aJobThatIsNotDoneIsAskedForAgainUntilItIs() {
    server.route(
        "/bigquery/v2/projects/helper2ai/queries",
        FixtureServer.Reply.ok(
            """
            {"jobComplete": false,
             "jobReference": {"projectId": "helper2ai", "jobId": "job-1", "location": "US"}}
            """));
    server.route("/bigquery/v2/projects/helper2ai/queries/job-1", FixtureServer.Reply.ok(ONE_ROW));

    List<Map<String, Object>> rows = client().query("SELECT 1", List.of(), 100);

    assertThat(rows).extracting(r -> r.get("name")).containsExactly("torch-tools");
    assertThat(server.requests)
        .anyMatch(r -> r.startsWith("/bigquery/v2/projects/helper2ai/queries/job-1?location=US"));
  }

  @Test
  void morePagesAreReadUpToTheRowCap() {
    String firstPage = ONE_ROW.replace("\"totalRows\": \"1\",", "\"pageToken\": \"p2\",");
    server.route("/bigquery/v2/projects/helper2ai/queries", FixtureServer.Reply.ok(firstPage));
    server.route(
        "/bigquery/v2/projects/helper2ai/queries/job-1", FixtureServer.Reply.ok(firstPage));

    List<Map<String, Object>> rows = client().query("SELECT 1", List.of(), 3);

    assertThat(rows).hasSize(3);
    assertThat(server.requests).anyMatch(r -> r.contains("pageToken=p2"));
  }

  @Test
  void aQueryOverTheCostCapIsAFailedRun() {
    server.route(
        "/bigquery/v2/projects/helper2ai/queries",
        new FixtureServer.Reply(
            400,
            """
            {"error": {"code": 400, "message": "Query exceeded limit for bytes billed",
             "errors": [{"reason": "bytesBilledLimitExceeded"}]}}
            """,
            Map.of(),
            0));

    assertThatThrownBy(() -> client().query("SELECT 1", List.of(), 100))
        .isInstanceOf(SourceFailure.class);
  }
}
