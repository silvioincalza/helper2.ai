package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.ListEntryText;
import ai.helper2.scout.RunStatus;
import ai.helper2.scout.SeedDiscoveryHosts;
import ai.helper2.scout.usecase.OneScoutRun;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.Vitality;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.classifier.RepoClassifier;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubAwesomeCollectorTest {

  static final String SEARCH =
      """
      {"items":[
        {"full_name":"acme/awesome-llm","html_url":"https://github.com/acme/awesome-llm","stargazers_count":5000,
         "description":"A curated list of LLM tools","created_at":"2023-01-01T00:00:00Z","pushed_at":"2026-10-01T00:00:00Z",
         "license":{"spdx_id":"CC0-1.0"},"topics":["llm","awesome-list"]},
        {"full_name":"f/prompts.chat","stargazers_count":170000}]}\
      """;

  static final String README =
      """
      # Awesome LLM
      ## Agents
      - [Interpreter](https://github.com/openinterpreter/openinterpreter) - Runs code from chat. ![badge](https://img.shields.io/x.svg)
      - [Node](https://github.com/nodejs/node)
      - [Tiny](https://github.com/someone/tiny)
      - [Old](https://github.com/someone/archived)
      - [Gone](https://github.com/someone/deleted)
      """;

  static final String GRAPHQL =
      """
      {"data":{
        "r0":{"nameWithOwner":"nodejs/node","url":"https://github.com/nodejs/node","description":"Node.js JavaScript runtime",
              "stargazerCount":123092,"isArchived":false,"isFork":false,"licenseInfo":null,"repositoryTopics":{"nodes":[]}},
        "r1":{"nameWithOwner":"openinterpreter/openinterpreter","url":"https://github.com/openinterpreter/openinterpreter",
              "description":"A natural language interface for computers","stargazerCount":68671,"isArchived":false,
              "isFork":false,"createdAt":"2023-07-14T00:00:00Z","pushedAt":"2026-10-20T00:00:00Z",
              "licenseInfo":{"spdxId":"AGPL-3.0"},"repositoryTopics":{"nodes":[{"topic":{"name":"llm"}}]}},
        "r2":{"nameWithOwner":"someone/archived","url":"https://github.com/someone/archived","description":"LLM thing",
              "stargazerCount":900,"isArchived":true,"isFork":false,"repositoryTopics":{"nodes":[]}},
        "r3":null,
        "r4":{"nameWithOwner":"someone/tiny","url":"https://github.com/someone/tiny","description":"LLM toy",
              "stargazerCount":3,"isArchived":false,"isFork":false,"repositoryTopics":{"nodes":[]}}},
       "errors":[{"type":"NOT_FOUND","path":["r3"]}]}\
      """;

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  static final class FakeModel implements RepoClassifier {
    final List<List<Facts>> calls = new java.util.ArrayList<>();
    RuntimeException failure;

    @Override
    public Map<String, Verdict> classify(List<Facts> repos) {
      calls.add(repos);
      if (failure != null) {
        throw failure;
      }
      Map<String, Verdict> out = new HashMap<>();
      for (Facts f : repos) {
        out.put(
            f.id(),
            f.fullName().startsWith("nodejs/")
                ? new Verdict(false, ResourceType.OTHER)
                : new Verdict(true, ResourceType.AGENT));
      }
      return out;
    }

    @Override
    public String name() {
      return "fake-nano";
    }
  }

  GitHubAwesomeCollector collector(RepoClassifier classifier, int perRun, String token) {
    return new GitHubAwesomeCollector(
        new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofDays(1)),
        List.of("topic:awesome-list topic:llm"),
        new GitHubAwesomeCollector.Limits(10, 1000, 1000, perRun, 50, 25),
        DEEP,
        classifier,
        ai.helper2.shared.CategoryMatcher.NONE,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        SeedDiscoveryHosts.store());
  }

  static final GitHubAwesomeCollector.Deep DEEP =
      new GitHubAwesomeCollector.Deep(
          2, 30, Duration.ofMinutes(15), 50_000_000L, 500, 50, List.of("youtube.com"));

  void routes() {
    server.route("/search/repositories", FixtureServer.Reply.ok(SEARCH));
    server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(README));
    server.route("/graphql", FixtureServer.Reply.ok(GRAPHQL));
  }

  static List<SourceHit> entryHits(CollectedBatch batch) {
    return batch.getHits().stream()
        .filter(h -> !h.getTags().contains(GitHubAwesomeCollector.LIST_TAG))
        .toList();
  }

  @Test
  void theListItselfIsAnArticleWithItsGitHubFacts() {
    routes();

    CollectedBatch batch = collector(new FakeModel(), 200, "t").collect(null, null, 500);

    assertThat(batch.getHits())
        .filteredOn(h -> h.getTags().contains(GitHubAwesomeCollector.LIST_TAG))
        .singleElement()
        .satisfies(list -> {
          assertThat(list.getExternalId()).isEqualTo("acme/awesome-llm");
          assertThat(list.getType()).isEqualTo(ResourceType.ARTICLE);
          assertThat(list.getDescription()).isEqualTo("A curated list of LLM tools");
          assertThat(list.getLicense()).isEqualTo("CC0-1.0");
          assertThat(list.getTags()).containsExactly("awesome-list", "llm");
          assertThat(list.getPopularity().getFirst().getValue()).isEqualTo(5000);
        });
  }

  @Test
  void eachListReadGivesItsSearchTextButNeverTheReadme() {
    routes();

    CollectedBatch batch = collector(new FakeModel(), 2, "t").collect(null, null, 500);

    assertThat(batch.getLists())
        .singleElement()
        .satisfies(list -> {
          assertThat(list.getUrl().getValue()).isEqualTo("https://github.com/acme/awesome-llm");
          assertThat(list.getHeadings()).isEqualTo("Awesome LLM\nAgents\n");
          assertThat(list.getEntries()).hasSize(5);
          assertThat(list.getEntries().getFirst())
              .isEqualTo(
                  new ListEntryText(
                      CanonicalUrl.of("https://github.com/openinterpreter/openinterpreter"),
                      "Interpreter - Runs code from chat.",
                      "Agents"));
        });
  }

  @Test
  void keepsOnlyRepositoriesTheModelCallsAiAndThatHaveTheStarsOfTheCollectionScope() {
    routes();
    FakeModel model = new FakeModel();

    CollectedBatch batch = collector(model, 200, "t").collect(null, null, 500);

    assertThat(entryHits(batch))
        .extracting(SourceHit::getExternalId)
        .containsExactly("openinterpreter/openinterpreter", "someone/archived");
    SourceHit hit = entryHits(batch).getFirst();
    assertThat(hit.getSourceId()).isEqualTo("github-awesome");
    assertThat(hit.getType()).isEqualTo(ResourceType.AGENT);
    assertThat(hit.getDescription()).isEqualTo("A natural language interface for computers");
    assertThat(hit.getLicense()).isEqualTo("AGPL-3.0");
    assertThat(hit.getTags()).containsExactly("llm");
    assertThat(hit.getPopularity())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.getKind()).isEqualTo(PopularityKind.GITHUB_STARS);
          assertThat(p.getValue()).isEqualTo(68671);
        });
    assertThat(model.calls)
        .singleElement()
        .satisfies(c ->
            assertThat(c)
                .extracting(RepoClassifier.Facts::fullName)
                .containsExactly(
                    "nodejs/node", "openinterpreter/openinterpreter", "someone/archived"));
    assertThat(model.calls.getFirst().getFirst().listSection()).isEqualTo("Agents");
    assertThat(batch.getNextCursor()).isNull();
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  static final String README_WITH_A_FORK =
      """
      # Awesome LLM
      ## Agents
      - [Fork](https://github.com/someone/fork) - A maintained fork.
      - [Old](https://github.com/someone/archived)
      """;

  static final String AN_ARCHIVED_REPOSITORY_AND_AN_ACTIVE_FORK =
      """
      {"data":{
        "r0":{"nameWithOwner":"someone/archived","url":"https://github.com/someone/archived","description":"LLM thing",
              "stargazerCount":900,"isArchived":true,"isFork":false,"repositoryTopics":{"nodes":[]}},
        "r1":{"nameWithOwner":"someone/fork","url":"https://github.com/someone/fork","description":"LLM agent, maintained",
              "stargazerCount":50,"isArchived":false,"isFork":true,"pushedAt":"2026-10-20T00:00:00Z",
              "repositoryTopics":{"nodes":[]}}}}\
      """;

  void routesOfAListWithAnArchivedRepositoryAndAnActiveFork() {
    server.route("/search/repositories", FixtureServer.Reply.ok(SEARCH));
    server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(README_WITH_A_FORK));
    server.route("/graphql", FixtureServer.Reply.ok(AN_ARCHIVED_REPOSITORY_AND_AN_ACTIVE_FORK));
  }

  @Test
  void aRepositoryOfAListStatesWhetherItIsArchivedOrAFork() {
    routesOfAListWithAnArchivedRepositoryAndAnActiveFork();

    CollectedBatch batch = collector(new FakeModel(), 200, "t").collect(null, null, 500);

    assertThat(entryHits(batch))
        .extracting(SourceHit::getExternalId, SourceHit::getVitality)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("someone/archived", new Vitality(true, false)),
            org.assertj.core.groups.Tuple.tuple("someone/fork", new Vitality(false, true)));
    assertThat(batch.getWithdrawn()).containsExactly("someone/archived");
  }

  @Test
  void anActiveForkWith50StarsInAListIsIndexedAndAnArchivedRepositoryIsRefusedAndCounted() {
    routesOfAListWithAnArchivedRepositoryAndAnActiveFork();

    OneScoutRun run = OneScoutRun.of(collector(new FakeModel(), 200, "t"), SourceFixtures.CLOCK);

    assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(run.getIndexed()).contains("someone/fork").doesNotContain("someone/archived");
    assertThat(run.dropped("archived")).isEqualTo(1.0);
    assertThat(run.dropped("idle-fork")).isZero();
  }

  @Test
  void aReadmeLargerThanTheDefaultResponseCapIsStillRead() {
    routes();
    String padded = README + "\n<!-- " + "x".repeat(3 * 1024 * 1024) + " -->\n";
    server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(padded));

    CollectedBatch batch = collector(new FakeModel(), 200, "t").collect(null, null, 500);

    assertThat(entryHits(batch))
        .extracting(SourceHit::getExternalId)
        .containsExactly("openinterpreter/openinterpreter", "someone/archived");
  }

  @Test
  void readsOnlyListsNamedAwesomeAndAsksGraphQlForAllEntriesInOneCall() {
    routes();
    collector(new FakeModel(), 200, "t").collect(null, null, 500);

    assertThat(server.requests).noneMatch(r -> r.contains("prompts.chat"));
    assertThat(server.requests.getFirst()).contains("sort=stars").contains("1000");
    assertThat(server.requests).filteredOn(r -> r.startsWith("/graphql")).hasSize(1);
    assertThat(server.requestBodies)
        .anyMatch(b -> b.contains("r3: repository(owner: \\\"someone\\\", name: \\\"deleted\\\")"));
  }

  @Test
  void fallsBackToKeywordRulesWhenTheModelFails() {
    routes();
    FakeModel model = new FakeModel();
    model.failure = new IllegalStateException("provider down");

    CollectedBatch batch = collector(model, 200, "t").collect(null, null, 500);

    assertThat(entryHits(batch))
        .extracting(SourceHit::getExternalId)
        .containsExactly("openinterpreter/openinterpreter", "someone/archived");
  }

  @Test
  void stopsAtThePerRunBudgetAndResumesFromTheCursor() {
    routes();
    GitHubAwesomeCollector c = collector(new FakeModel(), 2, "t");

    CollectedBatch first = c.collect(null, null, 500);
    assertThat(first.getNextCursor()).startsWith(GitHubAwesomeCollector.RESUME_AFTER);

    CollectedBatch second = c.collect(null, first.getNextCursor(), 500);
    assertThat(second.getNextCursor()).startsWith(GitHubAwesomeCollector.RESUME_AFTER);
    CollectedBatch last = c.collect(null, second.getNextCursor(), 500);
    assertThat(last.getNextCursor()).isNull();

    CollectedBatch whole = collector(new FakeModel(), 200, "t").collect(null, null, 500);
    assertThat(
            java.util.stream.Stream.of(first, second, last)
                .flatMap(b -> entryHits(b).stream())
                .map(SourceHit::getExternalId)
                .distinct())
        .as("the runs of a pass together list the live repositories one run lists")
        .containsExactlyInAnyOrderElementsOf(
            entryHits(whole).stream()
                .filter(h -> !Boolean.TRUE.equals(h.getVitality().getArchived()))
                .map(SourceHit::getExternalId)
                .toList());
  }

  @Test
  void aDeletedArchivedOrRenamedRepositoryIsWithdrawn() {
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    List<AwesomeListParser.Entry> slice =
        List.of(
            new AwesomeListParser.Entry("someone/deleted", "s", "h", "t"),
            new AwesomeListParser.Entry("Acme/Old-Tool", "s", "h", "t"),
            new AwesomeListParser.Entry("KillianLucas/open-interpreter", "s", "h", "t"),
            new AwesomeListParser.Entry("acme/live", "s", "h", "t"));
    Map<String, tools.jackson.databind.JsonNode> facts =
        Map.of(
            "acme/old-tool",
            json.readTree("{\"nameWithOwner\":\"acme/old-tool\",\"isArchived\":true}"),
            "killianlucas/open-interpreter",
            json.readTree(
                "{\"nameWithOwner\":\"openinterpreter/open-interpreter\",\"isArchived\":false}"),
            "acme/live",
            json.readTree("{\"nameWithOwner\":\"Acme/Live\",\"isArchived\":false}"));

    assertThat(GitHubAwesomeCollector.withdrawn(slice, facts, java.util.Set.of("someone/deleted")))
        .containsExactly(
            "someone/deleted", "Acme/Old-Tool", "acme/old-tool", "KillianLucas/open-interpreter");
  }

  @Test
  void aRepositoryGitHubDoesNotFindOrHasArchivedIsWithdrawn() {
    routes();

    CollectedBatch batch = collector(new FakeModel(), 200, "t").collect(null, null, 500);

    assertThat(batch.getWithdrawn()).containsExactly("someone/archived", "someone/deleted");
  }

  @Test
  void aBurstOfNotFoundAnswersWithdrawsNothing() {
    java.util.Set<String> many = new java.util.HashSet<>();
    for (int i = 0; i < 60; i++) {
      many.add("o/r" + i);
    }

    assertThat(GitHubAwesomeCollector.trusted(many, 1000)).as("over 50").isEmpty();
    assertThat(GitHubAwesomeCollector.trusted(java.util.Set.of("a/b", "c/d"), 10))
        .as("over a tenth of the entries")
        .isEmpty();
    assertThat(GitHubAwesomeCollector.trusted(java.util.Set.of("a/b"), 5)).containsExactly("a/b");
  }

  @Test
  void onlyATopLevelNotFoundWithNoDataMeansTheRepositoryIsGone() {
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    List<AwesomeListParser.Entry> batch =
        List.of(
            new AwesomeListParser.Entry("a/live", "s", "h", "t"),
            new AwesomeListParser.Entry("b/gone", "s", "h", "t"));
    var data = json.readTree("{\"r0\":{\"nameWithOwner\":\"a/live\"},\"r1\":null}");

    assertThat(
            GitHubAwesomeCollector.notFoundAlias(
                json.readTree("{\"type\":\"NOT_FOUND\",\"path\":[\"r1\"]}"), data, batch))
        .contains("b/gone");
    assertThat(
            GitHubAwesomeCollector.notFoundAlias(
                json.readTree("{\"type\":\"NOT_FOUND\",\"path\":[\"r0\",\"owner\"]}"), data, batch))
        .as("a nested field")
        .isEmpty();
    assertThat(
            GitHubAwesomeCollector.notFoundAlias(
                json.readTree("{\"type\":\"NOT_FOUND\",\"path\":[\"r0\"]}"), data, batch))
        .as("the alias has data")
        .isEmpty();
  }

  @Test
  void isDisabledWithoutATokenAndNotDueBeforeTheInterval() {
    assertThat(collector(new FakeModel(), 200, "").getDescriptor().getEnabled()).isFalse();

    String lastPass = SourceFixtures.CLOCK.instant().minus(Duration.ofHours(2)).toString();
    assertThat(collector(new FakeModel(), 200, "t").collect(lastPass, null, 500).getNotDue())
        .isTrue();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void aGraphQlRateLimitStopsThePass() {
    server.route("/search/repositories", FixtureServer.Reply.ok(SEARCH));
    server.route("/repos/acme/awesome-llm/readme", FixtureServer.Reply.ok(README));
    server.route("/graphql", FixtureServer.Reply.ok("{\"errors\":[{\"type\":\"RATE_LIMITED\"}]}"));

    assertThatThrownBy(() -> collector(new FakeModel(), 200, "t").collect(null, null, 500))
        .isInstanceOf(SourceFailure.RateLimited.class);
  }
}
