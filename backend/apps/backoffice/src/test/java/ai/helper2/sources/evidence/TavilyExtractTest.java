package ai.helper2.sources.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.ReadPage;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TavilyExtractTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  private TavilyExtract extract(String token) {
    SourceSettings settings =
        new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO);
    return new TavilyExtract(
        settings,
        ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        Resilience.NONE,
        SourceFixtures.CLOCK);
  }

  @Test
  void asksForBasicMarkdownWithoutImagesAndReturnsTheReadPages() {
    server.route(
        "/extract",
        FixtureServer.Reply.ok(
            """
            {"results":[{"url":"https://acme.example/pricing","raw_content":"# Pricing\\nFree plan."},
                        {"url":"https://acme.example/empty","raw_content":"  "}],
             "failed_results":[{"url":"https://acme.example/docs","error":"timeout"}],
             "response_time":1.2}
            """));

    List<ReadPage> pages =
        extract("key")
            .extract(
                List.of(
                    URI.create("https://acme.example/pricing"),
                    URI.create("https://acme.example/docs")));

    assertThat(pages)
        .containsExactly(new ReadPage("https://acme.example/pricing", "# Pricing\nFree plan."));
    String body = server.requestBodies.getFirst();
    assertThat(body)
        .contains("\"extract_depth\":\"basic\"")
        .contains("\"format\":\"markdown\"")
        .contains("\"include_images\":false")
        .contains("https://acme.example/docs");
    assertThat(server.requestHeaders.getFirst().get("Authorization").getFirst())
        .isEqualTo("Bearer key");
  }

  @Test
  void withoutAKeyItIsOffAndCallsNothing() {
    TavilyExtract off = extract(null);

    assertThat(off.getAvailable()).isFalse();
    assertThat(off.extract(List.of(URI.create("https://acme.example/")))).isEmpty();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void neverMoreThanFiveUrlsInOneCall() {
    List<URI> six =
        List.of(1, 2, 3, 4, 5, 6).stream()
            .map(i -> URI.create("https://acme.example/" + i))
            .toList();

    assertThatThrownBy(() -> extract("key").extract(six))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(server.requests).isEmpty();
  }
}
