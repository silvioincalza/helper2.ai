package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.catalog.enrichment.RepositoryVisits.Found;
import ai.helper2.catalog.enrichment.RepositoryVisits.Gone;
import ai.helper2.catalog.enrichment.RepositoryVisits.Parent;
import ai.helper2.catalog.enrichment.RepositoryVisits.Visit;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.SourceFailure;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubRepositoryStatesTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  GitHubRepositoryStates states() {
    return new GitHubRepositoryStates(
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build(),
        Resilience.NONE,
        SourceFixtures.CLOCK);
  }

  @Test
  void oneCallAnswersForEveryRepositoryAsked() {
    server.route(
        "/graphql",
        FixtureServer.Reply.ok(
            """
            {"data": {
              "r0": {"isArchived": false, "parent": null,
                     "latestRelease": {"publishedAt": "2026-10-01T08:00:00Z"}},
              "r1": {"isArchived": true, "parent": null, "latestRelease": null},
              "r2": {"isArchived": false,
                     "parent": {"nameWithOwner": "acme/origin", "isArchived": true},
                     "latestRelease": null}}}
            """));

    Map<String, Visit> visits = states().of(List.of("acme/living", "acme/archived", "me/fork"));

    assertThat(visits)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "acme/living",
                new Found(false, null, Instant.parse("2026-10-01T08:00:00Z")),
                "acme/archived",
                new Found(true, null, null),
                "me/fork",
                new Found(false, new Parent("acme/origin", true), null)));
    assertThat(server.requests).hasSize(1);
    assertThat(server.requestBodies.getFirst())
        .contains("r0: repository(owner: \\\"acme\\\", name: \\\"living\\\")")
        .contains("r2: repository(owner: \\\"me\\\", name: \\\"fork\\\")");
  }

  @Test
  void aRepositoryGitHubDoesNotFindIsGone() {
    server.route(
        "/graphql",
        FixtureServer.Reply.ok(
            """
            {"data": {"r0": null, "r1": {"isArchived": false, "parent": null, "latestRelease": null}},
             "errors": [{"type": "NOT_FOUND", "path": ["r0"], "message": "Could not resolve"}]}
            """));

    Map<String, Visit> visits = states().of(List.of("acme/deleted", "acme/living"));

    assertThat(visits.get("acme/deleted")).isEqualTo(new Gone());
    assertThat(visits.get("acme/living")).isEqualTo(new Found(false, null, null));
  }

  @Test
  void aRepositoryWithoutAnAnswerAndWithoutANotFoundIsLeftOut() {
    server.route(
        "/graphql",
        FixtureServer.Reply.ok(
            """
            {"data": {"r0": null},
             "errors": [{"type": "FORBIDDEN", "path": ["r0"], "message": "blocked"}]}
            """));

    assertThat(states().of(List.of("acme/blocked"))).isEmpty();
  }

  @Test
  void aNameThatIsNotOwnerAndNameIsNeverSentToGitHub() {
    Map<String, Visit> visits =
        states().of(List.of("acme/x\") { id } evil: repository(owner: \"a", "no-slash"));

    assertThat(visits).isEmpty();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void aDotNameIsNeverSentToGitHub() {
    assertThat(states().of(List.of("acme/..", "acme/."))).isEmpty();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void anAnswerThatFindsTooFewOfABatchFailsTheWholeQuestion() {
    List<String> asked = IntStream.range(0, 10).mapToObj(i -> "acme/tool-" + i).toList();
    String data =
        IntStream.range(0, 10)
            .mapToObj(i -> "\"r" + i + "\": null")
            .collect(Collectors.joining(","));
    String errors =
        IntStream.range(0, 10)
            .mapToObj(i -> "{\"type\": \"NOT_FOUND\", \"path\": [\"r" + i + "\"]}")
            .collect(Collectors.joining(","));
    server.route(
        "/graphql",
        FixtureServer.Reply.ok("{\"data\": {" + data + "}, \"errors\": [" + errors + "]}"));

    assertThatThrownBy(() -> states().of(asked)).isInstanceOf(SourceFailure.Unavailable.class);
  }

  @Test
  void aBadTokenFailsTheWholeQuestion() {
    server.route(
        "/graphql",
        new FixtureServer.Reply(401, "{\"message\": \"Bad credentials\"}", Map.of(), 0));

    assertThatThrownBy(() -> states().of(List.of("acme/living"))).isInstanceOf(SourceFailure.class);
  }

  @Test
  void aRateLimitFailsTheWholeQuestion() {
    server.route(
        "/graphql",
        FixtureServer.Reply.ok(
            """
            {"data": null, "errors": [{"type": "RATE_LIMITED", "message": "API rate limit exceeded"}]}
            """));

    assertThatThrownBy(() -> states().of(List.of("acme/living")))
        .isInstanceOf(SourceFailure.RateLimited.class);
  }

  @Test
  void anAnswerWithoutDataFailsTheWholeQuestion() {
    server.route("/graphql", FixtureServer.Reply.ok("{\"message\": \"Bad credentials\"}"));

    assertThatThrownBy(() -> states().of(List.of("acme/living")))
        .isInstanceOf(SourceFailure.Unavailable.class);
  }
}
