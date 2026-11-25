package ai.searchavo.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.resilience.CallRefused;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
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
    return reader(Resilience.NONE);
  }

  GitHubAwesomeListReader reader(Resilience resilience) {
    return new GitHubAwesomeListReader(
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build(),
        resilience,
        SourceFixtures.CLOCK);
  }

  @Test
  void aRepositoryWithoutAReadmeIsNotAListAndIsNoFailureOfTheSource() {
    var policies = new RecordingResilience();

    assertThat(reader(policies).isAwesomeList("someone/awesome-gone", List.of()))
        .isEqualTo(Optional.of(false));
    assertThat(policies.failures).isEmpty();
  }

  @Test
  void anOpenCircuitLeavesTheRepositoryUnchecked() {
    var policies = new RecordingResilience();
    policies.refuse = true;

    assertThat(reader(policies).isAwesomeList("someone/awesome-llm", List.of())).isEmpty();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void aRateLimitLeavesTheRepositoryUnchecked() {
    server.route(
        "/repos/someone/awesome-llm/readme",
        new FixtureServer.Reply(403, "{}", Map.of("x-ratelimit-remaining", "0"), 0));

    assertThat(reader().isAwesomeList("someone/awesome-llm", List.of())).isEmpty();
  }

  @Test
  void aServerErrorLeavesTheRepositoryUnchecked() {
    server.route(
        "/repos/someone/awesome-llm/readme", new FixtureServer.Reply(500, "{}", Map.of(), 0));

    assertThat(reader().isAwesomeList("someone/awesome-llm", List.of())).isEmpty();
  }

  private static final class RecordingResilience implements Resilience {

    final List<RuntimeException> failures = new ArrayList<>();
    boolean refuse;

    @Override
    public <T> T call(String policy, Supplier<T> work) {
      if (refuse) {
        throw new CallRefused(policy, CallRefused.Reason.CIRCUIT_OPEN, null);
      }
      try {
        return work.get();
      } catch (RuntimeException e) {
        failures.add(e);
        throw e;
      }
    }

    @Override
    public void require(String policy) {}
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
