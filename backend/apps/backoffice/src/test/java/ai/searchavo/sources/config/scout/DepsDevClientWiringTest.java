package ai.searchavo.sources.config.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.sources.config.CollectorProperties;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DepsDevClientWiringTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void theGitHubTokenNeverGoesToDepsDev() {
    server.route("/v3/projects/", FixtureServer.Reply.ok("{\"versions\":[]}"));
    SourceSettings gitHub =
        new SourceSettings(
            true,
            "https://api.github.com",
            "ghp_secret",
            Duration.ofSeconds(2),
            Duration.ZERO,
            Duration.ofSeconds(4));
    CollectorProperties.GitHubSdks settings =
        new CollectorProperties.GitHubSdks(
            Duration.ofDays(7),
            List.of("llm"),
            List.of("Java:1000"),
            List.of("openai"),
            300,
            100,
            3,
            20,
            10,
            Duration.ofSeconds(2),
            500,
            server.baseUrl(),
            3);

    CollectorsConfiguration.depsDevClient(
            gitHub,
            settings,
            SourceFixtures.CLOCK,
            Resilience.NONE,
            ObservationRegistry.NOOP,
            TestHttp.SETTINGS)
        .projectVersions("openai/openai-java");

    assertThat(server.requestHeaders)
        .singleElement()
        .satisfies(h -> assertThat(h.keySet()).noneMatch(k -> k.equalsIgnoreCase("Authorization")));
  }
}
