package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubAwesomeListReaderTest {

  static final String LIST =
      "# Links\n"
          + IntStream.rangeClosed(1, 25)
              .mapToObj(i -> "- [Tool " + i + "](https://example.com/tool-" + i + ")")
              .collect(Collectors.joining("\n"));

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  GitHubAwesomeListReader reader() {
    return new GitHubAwesomeListReader(
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build(),
        Resilience.NONE,
        SourceFixtures.CLOCK);
  }

  @Test
  void aRepositoryWithTheAwesomeListTopicIsReadAndPassesTheRule() {
    server.route("/repos/someone/llm-links/readme", FixtureServer.Reply.ok(LIST));

    assertThat(reader().isAwesomeList("someone/llm-links", List.of("llm", "awesome-list")))
        .isEqualTo(Optional.of(true));
  }

  @Test
  void aBestOfNameIsReadAndPassesTheRule() {
    server.route("/repos/someone/best-of-ml/readme", FixtureServer.Reply.ok(LIST));

    assertThat(reader().isAwesomeList("someone/best-of-ml", List.of()))
        .isEqualTo(Optional.of(true));
  }

  @Test
  void aRepositoryWithNeitherTheNameNorTheTopicIsNeverRead() {
    server.route("/repos/someone/llm-links/readme", FixtureServer.Reply.ok(LIST));

    assertThat(reader().isAwesomeList("someone/llm-links", List.of("llm")))
        .isEqualTo(Optional.of(false));
    assertThat(server.requests).isEmpty();
  }
}
