package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The k8s setup: actuator on its own port with metrics open for the cluster scraper. Only the app
 * port is routed through the tunnel, so it must not serve any actuator endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "helper2.sources.github.enabled=false", "helper2.sources.huggingface.enabled=false",
    "helper2.sources.npm.enabled=false", "helper2.sources.mcp-registry.enabled=false", "helper2.sources.arxiv.enabled=false", "helper2.sources.tavily.enabled=false",
    "management.server.port=0", "helper2.security.metrics-public=true", "logging.structured.format.console=",
})
@Import(TestcontainersConfiguration.class)
class ManagementPortIT {

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    final HttpClient http = HttpClient.newHttpClient();

    int status(int onPort, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + onPort + path)).build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void metricsAndProbesAreOnTheManagementPortOnly() throws Exception {
        assertThat(status(port, "/actuator/prometheus")).isNotEqualTo(200);
        assertThat(status(port, "/actuator/health")).isNotEqualTo(200);
        // Actuator requests are not observed, so an ordinary request is what puts http_server_requests in the scrape.
        status(port, "/");

        var metrics = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("http_server_requests_seconds", "jvm_memory_used_bytes",
            "helper2_guide_budget_remaining", "environment=\"local\"");
        assertThat(metrics.body()).doesNotContain("uri=\"/actuator");
        assertThat(status(managementPort, "/actuator/health/readiness")).isEqualTo(200);
        assertThat(status(managementPort, "/actuator/metrics")).isEqualTo(401);
    }
}
