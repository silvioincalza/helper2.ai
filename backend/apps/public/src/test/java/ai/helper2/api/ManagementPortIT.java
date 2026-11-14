package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.testing.SourcesOff;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SourcesOff
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "helper2.identity.metrics-public=true",
      "logging.structured.format.console=",
    })
@Import(TestcontainersConfiguration.class)
class ManagementPortIT {

  @LocalServerPort int port;

  @LocalManagementPort int managementPort;

  final HttpClient http = HttpClient.newHttpClient();

  int status(int onPort, String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + onPort + path)).build();
    return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  @Test
  void metricsAndProbesAreOnTheManagementPortOnly() throws Exception {
    assertThat(status(port, "/actuator/prometheus")).isNotEqualTo(200);
    assertThat(status(port, "/actuator/health")).isNotEqualTo(200);
    status(port, "/");

    var metrics =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + managementPort + "/actuator/prometheus"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.body())
        .contains(
            "http_server_requests_seconds",
            "jvm_memory_used_bytes",
            "helper2_guide_budget_remaining",
            "environment=\"local\"");
    assertThat(metrics.body()).doesNotContain("uri=\"/actuator");
    assertThat(status(managementPort, "/actuator/health/readiness")).isEqualTo(200);
    assertThat(status(managementPort, "/actuator/metrics")).isIn(401, 404);
    assertThat(status(managementPort, "/actuator/info")).isIn(401, 404);
  }
}
