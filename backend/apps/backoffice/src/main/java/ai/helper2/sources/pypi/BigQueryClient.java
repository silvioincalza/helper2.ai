package ai.helper2.sources.pypi;

import ai.helper2.search.SourceFailure;
import ai.helper2.sources.support.HttpClients;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class BigQueryClient {

  static final URI API = URI.create("https://bigquery.googleapis.com/bigquery/v2");

  private static final int WAIT_MS = 20_000;
  private static final int MAX_WAITS = 6;
  private static final ObjectMapper JSON = new ObjectMapper();

  record Parameter(String name, String type, String value) {}

  private record Job(String id, @Nullable String location) {}

  private final RestClient http;
  private final GoogleAccessTokens tokens;
  private final String project;
  private final Clock clock;
  private final URI api;
  private final long maxBytesBilled;

  BigQueryClient(
      RestClient http,
      GoogleAccessTokens tokens,
      String project,
      Clock clock,
      URI api,
      long maxBytesBilled) {
    this.http = http;
    this.tokens = tokens;
    this.project = project;
    this.clock = clock;
    this.api = api;
    this.maxBytesBilled = maxBytesBilled;
  }

  List<Map<String, Object>> query(String sql, List<Parameter> parameters, int maxRows) {
    JsonNode answer = post(request(sql, parameters, maxRows));
    Job job = jobOf(answer);
    for (int wait = 0; !answer.path("jobComplete").asBoolean(false); wait++) {
      if (wait >= MAX_WAITS) {
        throw new SourceFailure.Unavailable("BigQuery job " + job.id() + " did not finish");
      }
      answer = results(job, null, maxRows);
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    while (true) {
      addRows(answer, rows, maxRows);
      String pageToken = answer.path("pageToken").asString(null);
      if (rows.size() >= maxRows || pageToken == null) {
        return List.copyOf(rows);
      }
      answer = results(job, pageToken, maxRows - rows.size());
    }
  }

  private Map<String, Object> request(String sql, List<Parameter> parameters, int maxRows) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", sql);
    body.put("useLegacySql", false);
    body.put("parameterMode", "NAMED");
    body.put(
        "queryParameters",
        parameters.stream()
            .map(p ->
                Map.of(
                    "name", p.name(),
                    "parameterType", Map.of("type", p.type()),
                    "parameterValue", Map.of("value", p.value())))
            .toList());
    body.put("maximumBytesBilled", Long.toString(maxBytesBilled));
    body.put("maxResults", maxRows);
    body.put("timeoutMs", WAIT_MS);
    body.put("formatOptions", Map.of("useInt64Timestamp", true));
    return body;
  }

  private JsonNode post(Map<String, Object> body) {
    URI uri = UriComponentsBuilder.fromUri(api).path("/projects/{project}/queries").build(project);
    return read(
        HttpClients.getText(
            () ->
                http.post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.current())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(body))
                    .retrieve()
                    .body(String.class),
            clock));
  }

  private JsonNode results(Job job, @Nullable String pageToken, int maxRows) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromUri(api)
            .path("/projects/{project}/queries/{job}")
            .queryParamIfPresent("location", java.util.Optional.ofNullable(job.location()))
            .queryParam("timeoutMs", WAIT_MS)
            .queryParam("maxResults", maxRows)
            .queryParam("formatOptions.useInt64Timestamp", true)
            .queryParamIfPresent("pageToken", java.util.Optional.ofNullable(pageToken));
    URI target = uri.build(project, job.id());
    return read(
        HttpClients.getText(
            () ->
                http.get()
                    .uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.current())
                    .retrieve()
                    .body(String.class),
            clock));
  }

  private static Job jobOf(JsonNode answer) {
    JsonNode reference = answer.path("jobReference");
    String id = reference.path("jobId").asString(null);
    if (id == null) {
      throw new SourceFailure.Unavailable("malformed response: no BigQuery job reference");
    }
    return new Job(id, reference.path("location").asString(null));
  }

  private static void addRows(JsonNode answer, List<Map<String, Object>> rows, int maxRows) {
    List<String> names = new ArrayList<>();
    for (JsonNode field : answer.path("schema").path("fields")) {
      names.add(field.path("name").asString());
    }
    for (JsonNode row : answer.path("rows")) {
      if (rows.size() >= maxRows) {
        return;
      }
      Map<String, Object> values = new LinkedHashMap<>();
      JsonNode cells = row.path("f");
      for (int i = 0; i < names.size() && i < cells.size(); i++) {
        Object value = cellValue(cells.get(i).path("v"));
        if (value != null) {
          values.put(names.get(i), value);
        }
      }
      rows.add(values);
    }
  }

  private static @Nullable Object cellValue(JsonNode v) {
    if (v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isArray()) {
      List<String> items = new ArrayList<>();
      for (JsonNode item : v) {
        String text = item.path("v").asString(null);
        if (text != null) {
          items.add(text);
        }
      }
      return List.copyOf(items);
    }
    return v.asString(null);
  }

  private static JsonNode read(String body) {
    try {
      return JSON.readTree(body);
    } catch (JacksonException e) {
      throw new SourceFailure.Unavailable("malformed response: BigQuery answer is not JSON");
    }
  }
}
