package ai.searchavo.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.catalog.enrichment.RepositoryVisits.Found;
import ai.searchavo.catalog.enrichment.RepositoryVisits.Gone;
import ai.searchavo.catalog.enrichment.RepositoryVisits.Parent;
import ai.searchavo.catalog.enrichment.RepositoryVisits.Visit;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
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
                new Found(false, null, Instant.parse("2026-10-01T08:00:00Z"), null),
                "acme/archived",
                new Found(true, null, null, null),
                "me/fork",
                new Found(false, new Parent("acme/origin", true), null, null)));
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
    assertThat(visits.get("acme/living")).isEqualTo(new Found(false, null, null, null));
  }

  // S2.11.6, ADR-0020 §7: the sweep reads the stars in the call it already makes.
  @Test
  void theStarsOfARepositoryAreReadInTheSameCall() {
    server.route(
        "/graphql",
        FixtureServer.Reply.ok(
            """
            {"data": {
              "r0": {"isArchived": false, "stargazerCount": 3400, "parent": null, "latestRelease": null},
              "r1": {"isArchived": false, "stargazerCount": 0, "parent": null, "latestRelease": null}}}
            """));

    Map<String, Visit> visits = states().of(List.of("acme/starred", "acme/new"));

    assertThat(visits.get("acme/starred")).isEqualTo(new Found(false, null, null, 3400L));
    assertThat(visits.get("acme/new")).isEqualTo(new Found(false, null, null, 0L));
    assertThat(server.requests).hasSize(1);
    assertThat(server.requestBodies.getFirst()).contains("stargazerCount");
  }

  // S2.11.6, ADR-0020 §7: an answer without a count is no count, never zero stars.
  @Test
  void anAnswerWithoutACountOfStarsStatesNoStars() {
    server.route(
        "/graphql",
        FixtureServer.Reply.ok(
            """
            {"data": {
              "r0": {"isArchived": false, "stargazerCount": null, "parent": null, "latestRelease": null},
              "r1": {"isArchived": false, "stargazerCount": -1, "parent": null, "latestRelease": null},
              "r2": {"isArchived": false, "stargazerCount": "many", "parent": null, "latestRelease": null}}}
            """));

    Map<String, Visit> visits = states().of(List.of("acme/null", "acme/negative", "acme/text"));

    assertThat(visits.values()).containsOnly(new Found(false, null, null, null));
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
  void anAnswerThatFindsTooFewOfABatchLeavesThemUnansweredInsteadOfGone() {
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

    assertThat(states().of(asked)).isEmpty();
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
