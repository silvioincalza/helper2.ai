package ai.helper2.sources.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.testing.FixtureServer;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpClientsMappingTest {

    final FixtureServer server = new FixtureServer();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    {
        Metrics.addRegistry(meters);
    }

    @AfterEach
    void stop() {
        server.close();
        Metrics.removeRegistry(meters);
    }

    private RestClient client() {
        return ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).baseUrl(server.baseUrl()).build();
    }

    @Test
    void a503AskingForAPauseIsARateLimit() {
        server.route("/busy", new FixtureServer.Reply(503, "", Map.of("Retry-After", "30"), 0));
        server.route("/down", new FixtureServer.Reply(503, "", Map.of(), 0));
        RestClient http = client();

        assertThatThrownBy(() -> HttpClients.getText(() -> http.get().uri("/busy").retrieve().body(String.class), SourceFixtures.CLOCK))
            .isInstanceOfSatisfying(SourceFailure.RateLimited.class, e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(30)));
        assertThatThrownBy(() -> HttpClients.getText(() -> http.get().uri("/down").retrieve().body(String.class), SourceFixtures.CLOCK))
            .isInstanceOfSatisfying(SourceFailure.Unavailable.class, e -> assertThat(e.getTransient()).as("503 is transient").isTrue());
    }

    /** What the Scout's collector policies retry: 503 and network errors or timeouts, never another status or a bad body. */
    @Test
    void onlyA503OrANetworkFailureIsTransient() {
        server.route("/gone", new FixtureServer.Reply(404, "{}", Map.of(), 0));
        server.route("/broken", new FixtureServer.Reply(500, "{}", Map.of(), 0));
        server.route("/garbage", FixtureServer.Reply.ok("not json"));
        server.route("/slow", new FixtureServer.Reply(200, "{}", Map.of(), 1_500));
        RestClient http = client();
        RestClient impatient = ObservedHttp.restClientBuilder(Duration.ofMillis(300), ObservationRegistry.NOOP).baseUrl(server.baseUrl()).build();

        for (String path : List.of("/gone", "/broken", "/garbage")) {
            assertThatThrownBy(() -> HttpClients.getJson(() -> http.get().uri(path).retrieve().body(String.class), SourceFixtures.CLOCK))
                .as(path).isInstanceOfSatisfying(SourceFailure.Unavailable.class, e -> assertThat(e.getTransient()).isFalse());
        }
        assertThatThrownBy(() -> HttpClients.getJson(() -> impatient.get().uri("/slow").retrieve().body(String.class), SourceFixtures.CLOCK))
            .isInstanceOfSatisfying(SourceFailure.Unavailable.class, e -> assertThat(e.getTransient()).as("timeout").isTrue());
    }

    @Test
    void aSlowResponseFailsAfterTheResponseTimeout() {
        server.route("/slow", new FixtureServer.Reply(200, "late", Map.of(), 1_500));
        RestClient http = ObservedHttp.restClientBuilder(Duration.ofMillis(300), ObservationRegistry.NOOP).baseUrl(server.baseUrl()).build();
        long started = System.nanoTime();

        assertThatThrownBy(() -> HttpClients.getText(() -> http.get().uri("/slow").retrieve().body(String.class), SourceFixtures.CLOCK))
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

    @Test
    void aMalformedItemIsDroppedAndCountedWhileTheOthersAreKept() {
        JsonNode items = JsonMapper.builder().build().readTree("[{\"id\":\"a\"},{\"id\":\"bad\"},{\"id\":\"c\"},{\"id\":\"skip\"}]");

        List<SourceHit> hits = HttpClients.hits("test-source", items, SourceFixtures.CLOCK.instant(), (item, now, rank) -> {
            String id = item.path("id").asString();
            if (id.equals("bad")) {
                throw new IllegalArgumentException("source data that does not map");
            }
            return id.equals("skip") ? null : SourceFixtures.hit(id, rank);
        });

        assertThat(hits).extracting(SourceHit::getExternalId).containsExactly("a", "c");
        assertThat(meters.get("helper2.source.items.dropped").tag("source", "test-source")
            .tag("reason", "IllegalArgumentException").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aResponseOverTheDefaultCapIsUnavailableAsTooLarge() {
        server.route("/huge", FixtureServer.Reply.ok("x".repeat(ObservedHttp.MAX_RESPONSE_BYTES + 1)));
        server.route("/fits", FixtureServer.Reply.ok("x".repeat(ObservedHttp.MAX_RESPONSE_BYTES)));
        RestClient http = client();

        assertThatThrownBy(() -> HttpClients.getText(() -> http.get().uri("/huge").retrieve().body(String.class), SourceFixtures.CLOCK))
            .isInstanceOf(SourceFailure.Unavailable.class).hasMessage("response too large");
        assertThat(HttpClients.getText(() -> http.get().uri("/fits").retrieve().body(String.class), SourceFixtures.CLOCK))
            .hasSize(ObservedHttp.MAX_RESPONSE_BYTES);
    }

    @Test
    void aCallCanAllowALargerResponseUpToItsOwnCap() {
        server.route("/readme", FixtureServer.Reply.ok("x".repeat(3 * 1024 * 1024)));
        server.route("/huge-readme", FixtureServer.Reply.ok("x".repeat(5 * 1024 * 1024 + 1)));
        RestClient http = client();

        assertThat(HttpClients.getText(() -> http.get().uri("/readme").attributes(ObservedHttp.maxResponseBytes(5 * 1024 * 1024))
            .retrieve().body(String.class), SourceFixtures.CLOCK)).hasSize(3 * 1024 * 1024);
        assertThatThrownBy(() -> HttpClients.getText(() -> http.get().uri("/huge-readme").attributes(ObservedHttp.maxResponseBytes(5 * 1024 * 1024))
            .retrieve().body(String.class), SourceFixtures.CLOCK))
            .isInstanceOf(SourceFailure.Unavailable.class).hasMessage("response too large");
    }
}
