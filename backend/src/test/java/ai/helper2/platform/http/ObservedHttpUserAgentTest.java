package ai.helper2.platform.http;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObservedHttpUserAgentTest {

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void theUserAgentNamesTheBuildVersion() {
        assertThat(ObservedHttp.userAgent("1.2.3")).isEqualTo("Helper2.ai-metasearch/1.2.3 (+https://helper2.ai/sources)");
        assertThat(ObservedHttp.userAgent(null)).startsWith("Helper2.ai-metasearch/dev ");
        assertThat(ObservedHttp.USER_AGENT).doesNotContain("/0.1 ");
    }

    @Test
    void requestsCarryTheUserAgent() {
        server.route("/ua", new FixtureServer.Reply(200, "ok", Map.of(), 0));
        ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).baseUrl(server.baseUrl()).build()
            .get().uri("/ua").retrieve().body(String.class);
        assertThat(server.requestHeaders.getLast().entrySet()).anySatisfy(h -> {
            assertThat(h.getKey()).isEqualToIgnoringCase("User-Agent");
            assertThat(h.getValue()).containsExactly(ObservedHttp.USER_AGENT);
        });
    }
}
