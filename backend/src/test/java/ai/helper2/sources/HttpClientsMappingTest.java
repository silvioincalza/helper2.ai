package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.search.SourceFailure;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpClientsMappingTest {

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    private RestClient client() {
        return ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).baseUrl(server.baseUrl()).build();
    }

    @Test
    void a503AskingForAPauseIsARateLimit() {
        server.route("/busy", new FixtureServer.Reply(503, "", Map.of("Retry-After", "30"), 0));
        server.route("/down", new FixtureServer.Reply(503, "", Map.of(), 0));
        RestClient http = client();

        assertThatThrownBy(() -> HttpClients.getText(() -> http.get().uri("/busy").retrieve().body(String.class), GitHubSourceTest.CLOCK))
            .isInstanceOfSatisfying(SourceFailure.RateLimited.class, e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(30)));
        assertThatThrownBy(() -> HttpClients.getText(() -> http.get().uri("/down").retrieve().body(String.class), GitHubSourceTest.CLOCK))
            .isInstanceOf(SourceFailure.Unavailable.class);
    }

    @Test
    void aSlowResponseFailsAfterTheResponseTimeout() {
        server.route("/slow", new FixtureServer.Reply(200, "late", Map.of(), 1_500));
        RestClient http = ObservedHttp.restClientBuilder(Duration.ofMillis(300), ObservationRegistry.NOOP).baseUrl(server.baseUrl()).build();
        long started = System.nanoTime();

        assertThatThrownBy(() -> HttpClients.getText(() -> http.get().uri("/slow").retrieve().body(String.class), GitHubSourceTest.CLOCK))
            .isInstanceOf(SourceFailure.Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_200));
    }

    @Test
    void linksAreTheTrimmedSourceUrlOrTheCanonicalForm() {
        CanonicalUrl canonical = CanonicalUrl.of("https://github.com/acme/tool");
        assertThat(HttpClients.link("  https://github.com/Acme/Tool  ", canonical)).isEqualTo(URI.create("https://github.com/Acme/Tool"));
        assertThat(HttpClients.link("git+https://github.com/acme/tool.git", canonical)).isEqualTo(URI.create("https://github.com/acme/tool"));
        assertThat(HttpClients.link("https://example.com/a b", CanonicalUrl.of("https://example.com/a"))).isEqualTo(URI.create("https://example.com/a"));
    }
}
