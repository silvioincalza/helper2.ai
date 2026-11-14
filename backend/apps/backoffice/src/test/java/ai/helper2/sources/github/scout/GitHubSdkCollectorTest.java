package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateSignals;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.RunStatus;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.scout.usecase.CappedReview;
import ai.helper2.search.SourceFailure;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.depsdev.Ecosystem;
import ai.helper2.sources.depsdev.PackageFinder;
import ai.helper2.sources.depsdev.PackageVersion;
import ai.helper2.sources.depsdev.RegistryPackage;
import ai.helper2.sources.github.scout.SdkSearchPlan.LanguageFloor;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubSdkCollectorTest {

  static final String REPOS =
      """
      {"total_count":3,"items":[
        {"full_name":"anthropics/anthropic-sdk-java","html_url":"https://github.com/anthropics/anthropic-sdk-java",
         "owner":{"login":"anthropics"},"language":"Kotlin",
         "description":"Access to Claude, the AI model by Anthropic, from Java","stargazers_count":391,
         "topics":["anthropic","claude"]},
        {"full_name":"tjake/Jlama","html_url":"https://github.com/tjake/Jlama","owner":{"login":"tjake"},
         "language":"Java","description":"Jlama is a modern LLM inference engine for Java",
         "stargazers_count":1310,"topics":["llm","java"]},
        {"full_name":"acme/http-kit","html_url":"https://github.com/acme/http-kit","owner":{"login":"acme"},
         "language":"Java","description":"A fast HTTP toolkit","stargazers_count":5000,"topics":["http"]}
      ]}\
      """;

  static class FakeRegistry implements PackageFinder.Registry {
    final List<String> projects = new ArrayList<>();
    boolean down;

    @Override
    public List<PackageVersion> projectVersions(String repoFullName) {
      if (down) {
        throw new SourceFailure.Unavailable("HTTP 503", true);
      }
      projects.add(repoFullName);
      return repoFullName.equals("anthropics/anthropic-sdk-java")
          ? List.of(
              new PackageVersion(
                  Ecosystem.MAVEN, "com.anthropic:anthropic-java", PackageVersion.UNVERIFIED))
          : List.of();
    }

    @Override
    public Optional<RegistryPackage> latest(Ecosystem ecosystem, String name) {
      return Optional.of(
          new RegistryPackage(
              ecosystem,
              name,
              "3.6.0",
              Instant.parse("2026-11-06T02:17:07Z"),
              List.of("Apache-2.0"),
              "github.com/anthropics/anthropic-sdk-java"));
    }
  }

  final FixtureServer server = new FixtureServer();
  final FakeRegistry registry = new FakeRegistry();

  @AfterEach
  void stop() {
    server.close();
  }

  final Set<String> known = new java.util.HashSet<>();
  int room = 500;
  final List<Duration> pauses = new ArrayList<>();
  int pagesPerQuery = 1;
  int perQuery = 100;

  GitHubSdkCollector collector(Duration interval, SdkSearchPlan plan, int queriesPerRun) {
    SourceSettings settings =
        new SourceSettings(true, server.baseUrl(), "token", Duration.ofSeconds(2), Duration.ZERO);
    GitHubSdkCollector.ReviewRoom review =
        new GitHubSdkCollector.ReviewRoom() {
          @Override
          public boolean known(CanonicalUrl url) {
            return known.contains(url.getValue());
          }

          @Override
          public int roomToday() {
            return room;
          }
        };
    return new GitHubSdkCollector(
        settings,
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(interval),
        new GitHubSdkCollector.Limits(
            plan,
            Set.of("Anthropics"),
            perQuery,
            pagesPerQuery,
            queriesPerRun,
            10,
            Duration.ofSeconds(2),
            500),
        new GitHubSdkCollector.Ports(new PackageFinder(registry, 3), review, pauses::add),
        CollectorDescriptors.gitHubSdks(settings, List.of("Java:1000"), List.of("anthropics")));
  }

  static SdkSearchPlan plan(List<String> topics, List<String> orgs) {
    return SdkSearchPlan.of(
        topics, List.of(new LanguageFloor("Java", 1000), new LanguageFloor("C#", 500)), orgs, 300);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  @Test
  void searchesEachTopicPerLanguageWithItsStarFloorThenEachProviderOrg() {
    server.route("/search/repositories", FixtureServer.Reply.ok("{\"items\":[]}"));

    collector(Duration.ZERO, plan(List.of("llm"), List.of("anthropics")), 10)
        .collect(null, null, 1000);

    assertThat(server.requests)
        .extracting(r -> URLDecoder.decode(r, StandardCharsets.UTF_8))
        .satisfiesExactly(
            r ->
                assertThat(r)
                    .contains(
                        "q=topic:llm language:\"Java\" stars:>=1000 fork:false archived:false"),
            r ->
                assertThat(r)
                    .contains("q=topic:llm language:\"C#\" stars:>=500 fork:false archived:false"),
            r -> assertThat(r).contains("q=org:anthropics stars:>=300 fork:false archived:false"));
    assertThat(server.requests.getFirst()).contains("sort=stars").contains("per_page=100");
  }

  @Test
  void proposesOnlyAiReposWithTheirPackagesLanguageAndOfficialFlag() {
    server.route("/search/repositories", FixtureServer.Reply.ok(REPOS));

    CollectedBatch batch =
        collector(Duration.ZERO, plan(List.of("llm"), List.of()), 1).collect(null, null, 1000);

    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue())
        .containsExactly(
            "https://github.com/anthropics/anthropic-sdk-java", "https://github.com/tjake/jlama");
    Candidate sdk = batch.getCandidates().getFirst();
    assertThat(sdk.getTags()).containsExactly("kotlin", "java", "llm");
    @SuppressWarnings("unchecked")
    Map<String, Object> fact = (Map<String, Object>) sdk.getFacts().get(SdkFacts.KEY);
    assertThat(fact)
        .containsEntry("official", true)
        .containsEntry("languages", List.of("Kotlin", "Java"))
        .containsEntry("license", "Apache-2.0")
        .containsEntry("latestRelease", "2026-11-06T02:17:07Z");
    assertThat((List<?>) fact.get("packages"))
        .singleElement()
        .satisfies(p ->
            assertThat(asMap(p))
                .containsEntry("ecosystem", "MAVEN")
                .containsEntry("name", "com.anthropic:anthropic-java")
                .containsEntry("version", "3.6.0"));
    Map<?, ?> jlama = (Map<?, ?>) batch.getCandidates().get(1).getFacts().get(SdkFacts.KEY);
    assertThat(jlama.get("official")).isEqualTo(false);
    assertThat(batch.getNewCandidatesPerDay()).isEqualTo(500);
  }

  @Test
  void aCandidateCarriesTheStarsLastPushArchivedAndForkGitHubStates() {
    String stated =
        """
        {"items":[
          {"full_name":"tjake/Jlama","html_url":"https://github.com/tjake/Jlama","owner":{"login":"tjake"},
           "language":"Java","description":"Jlama is a modern LLM inference engine for Java",
           "stargazers_count":1310,"pushed_at":"2026-11-01T08:30:00Z","archived":true,"fork":false,
           "topics":["llm"]}
        ]}\
        """;
    server.route("/search/repositories", FixtureServer.Reply.ok(stated));

    CollectedBatch batch =
        collector(Duration.ZERO, plan(List.of("llm"), List.of()), 1).collect(null, null, 1000);

    Candidate jlama = batch.getCandidates().getFirst();
    assertThat(jlama.getSignals())
        .isEqualTo(new CandidateSignals(1310L, Instant.parse("2026-11-01T08:30:00Z"), true, false));
    assertThat(jlama.getFacts()).doesNotContainKey("stars");
  }

  @Test
  void whatGitHubDoesNotStateAboutACandidateStaysUnknown() {
    server.route("/search/repositories", FixtureServer.Reply.ok(REPOS));

    CollectedBatch batch =
        collector(Duration.ZERO, plan(List.of("llm"), List.of()), 1).collect(null, null, 1000);

    assertThat(batch.getCandidates().getFirst().getSignals()).isEqualTo(new CandidateSignals(391L));
  }

  @Test
  void officialReposWithAConfirmedPackageComeFirstThenOtherOfficialReposThenTheRest() {
    String communityFirst =
        """
        {"items":[
          {"full_name":"tjake/Jlama","html_url":"https://github.com/tjake/Jlama","owner":{"login":"tjake"},
           "language":"Java","description":"Jlama is a modern LLM inference engine for Java",
           "stargazers_count":1310,"topics":["llm"]},
          {"full_name":"anthropics/claude-cookbooks","html_url":"https://github.com/anthropics/claude-cookbooks",
           "owner":{"login":"anthropics"},"language":"Python","description":"Recipes for Claude",
           "stargazers_count":9000,"topics":[]},
          {"full_name":"anthropics/anthropic-sdk-java","html_url":"https://github.com/anthropics/anthropic-sdk-java",
           "owner":{"login":"anthropics"},"language":"Kotlin","description":"Claude from Java",
           "stargazers_count":391,"topics":[]}
        ]}\
        """;
    server.route("/search/repositories", FixtureServer.Reply.ok(communityFirst));

    CollectedBatch batch =
        collector(Duration.ZERO, plan(List.of("llm"), List.of()), 1).collect(null, null, 1000);

    assertThat(batch.getCandidates())
        .extracting(Candidate::getTitle)
        .containsExactly(
            "anthropics/anthropic-sdk-java", "anthropics/claude-cookbooks", "tjake/Jlama");
  }

  @Test
  void aLongPlanResumesFromItsCursorAndEndsWithACheckpoint() {
    server.route("/search/repositories", FixtureServer.Reply.ok("{\"items\":[]}"));
    GitHubSdkCollector collector =
        collector(Duration.ofDays(7), plan(List.of("llm", "rag"), List.of()), 3);

    CollectedBatch first = collector.collect(null, null, 1000);
    CollectedBatch second = collector.collect(null, first.getNextCursor(), 1000);

    assertThat(first.getNextCursor()).isEqualTo("3");
    assertThat(first.getCheckpoint()).isNull();
    assertThat(second.getNextCursor()).isNull();
    assertThat(second.getCheckpoint()).isNotNull();
    assertThat(server.requests).hasSize(4);
  }

  @Test
  void aRepoFoundTwiceInOnePassAsksDepsDevOnce() {
    server.route("/search/repositories", FixtureServer.Reply.ok(REPOS));
    GitHubSdkCollector collector = collector(Duration.ZERO, plan(List.of("llm"), List.of()), 1);

    CollectedBatch first = collector.collect(null, null, 1000);
    collector.collect(null, first.getNextCursor(), 1000);

    assertThat(registry.projects).containsExactly("anthropics/anthropic-sdk-java", "tjake/Jlama");
  }

  @Test
  void aPassTheDailyCapHeldBackStartsTheSearchesAgainAndKeepsThePackagesItRead() {
    server.route("/search/repositories", FixtureServer.Reply.ok(REPOS));
    GitHubSdkCollector collector = collector(Duration.ZERO, plan(List.of("llm"), List.of()), 2);

    collector.collect(null, null, 1000);
    CollectedBatch again = collector.collect(ScoutCursor.candidatesHeldBack(null, null), 1000);

    assertThat(again.getCandidates()).hasSize(2);
    assertThat(server.requests).hasSize(4);
    assertThat(registry.projects).containsExactly("anthropics/anthropic-sdk-java", "tjake/Jlama");
  }

  @Test
  void whenDepsDevIsDownTheRepoIsStillProposedWithoutPackages() {
    server.route("/search/repositories", FixtureServer.Reply.ok(REPOS));
    registry.down = true;

    CollectedBatch batch =
        collector(Duration.ZERO, plan(List.of("llm"), List.of()), 1).collect(null, null, 1000);

    Map<String, Object> fact = asMap(batch.getCandidates().getFirst().getFacts().get(SdkFacts.KEY));
    assertThat((List<?>) fact.get("packages")).isEmpty();
    assertThat(fact).doesNotContainKey("license");
  }

  static String page(int total, String... names) {
    StringBuilder items = new StringBuilder();
    for (String n : names) {
      if (items.length() > 0) {
        items.append(',');
      }
      items.append(
          "{\"full_name\":\"%1$s\",\"html_url\":\"https://github.com/%1$s\",\"owner\":{\"login\":\"x\"},\"language\":\"Go\",\"description\":\"LLM client\",\"stargazers_count\":2000,\"topics\":[]}"
              .formatted(n));
    }
    return "{\"total_count\":" + total + ",\"items\":[" + items + "]}";
  }

  @Test
  void readsFurtherPagesUpToItsPageBudgetAndSpacesTheSearchesOut() {
    perQuery = 2;
    pagesPerQuery = 2;
    server.route("/search/repositories", FixtureServer.Reply.ok(page(5, "a/one-llm", "a/two-llm")));

    collector(
            Duration.ZERO,
            SdkSearchPlan.of(List.of("llm"), List.of(new LanguageFloor("Go", 1)), List.of(), 1),
            5)
        .collect(null, null, 1000);

    assertThat(server.requests).hasSize(2);
    assertThat(server.requests.get(1)).contains("page=2");
    assertThat(pauses).containsExactly(Duration.ofSeconds(2));
  }

  @Test
  void aLastPageShorterThanAPageEndsTheQuery() {
    perQuery = 2;
    pagesPerQuery = 3;
    server.route("/search/repositories", FixtureServer.Reply.ok(page(1, "a/one-llm")));

    collector(
            Duration.ZERO,
            SdkSearchPlan.of(List.of("llm"), List.of(new LanguageFloor("Go", 1)), List.of(), 1),
            5)
        .collect(null, null, 1000);

    assertThat(server.requests).hasSize(1);
  }

  @Test
  void whenTheTokenRunsLowTheRunStopsAndTheCursorPointsAtTheNextSearch() {
    server.route(
        "/search/repositories",
        new FixtureServer.Reply(
            200, page(1, "a/one-llm"), Map.of("X-RateLimit-Remaining", "5"), 0));

    CollectedBatch batch =
        collector(Duration.ZERO, plan(List.of("llm", "rag"), List.of()), 4)
            .collect(null, null, 1000);

    assertThat(server.requests).hasSize(1);
    assertThat(batch.getNextCursor()).isEqualTo("1");
    assertThat(batch.getCheckpoint()).isNull();
    assertThat(batch.getCandidates()).hasSize(1);
  }

  @Test
  void reposAlreadyListedOrProposedAreNotLookedUpAtDepsDev() {
    server.route("/search/repositories", FixtureServer.Reply.ok(REPOS));
    known.add("https://github.com/anthropics/anthropic-sdk-java");

    CollectedBatch batch =
        collector(Duration.ZERO, plan(List.of("llm"), List.of()), 1).collect(null, null, 1000);

    assertThat(batch.getCandidates())
        .extracting(Candidate::getTitle)
        .containsExactly("tjake/Jlama");
    assertThat(registry.projects).containsExactly("tjake/Jlama");
  }

  @Test
  void onlyAsManyReposAsTheDailyCapLeavesAreLookedUpOfficialOnesFirst() {
    server.route("/search/repositories", FixtureServer.Reply.ok(REPOS));
    room = 1;

    CollectedBatch batch =
        collector(Duration.ZERO, plan(List.of("llm"), List.of()), 1).collect(null, null, 1000);

    assertThat(batch.getCandidates())
        .extracting(Candidate::getTitle)
        .containsExactly("anthropics/anthropic-sdk-java");
    assertThat(registry.projects).containsExactly("anthropics/anthropic-sdk-java");
  }

  @Test
  void afterARunTheReviewCapHeldBackTheNextRunProposesTheHeldCandidates() {
    server.route("/search/repositories", FixtureServer.Reply.ok(REPOS));
    GitHubSdkCollector collector = collector(Duration.ZERO, plan(List.of("llm"), List.of()), 2);

    List<CappedReview.Run> runs =
        CappedReview.aHeldBackRunThenTheNext(collector, SourceFixtures.CLOCK);

    assertThat(runs).extracting(CappedReview.Run::getStatus).containsOnly(RunStatus.SUCCEEDED);
    assertThat(runs.get(0).getCursorAfter().getCandidatesHeldBack()).isTrue();
    assertThat(runs.get(0).getProposed()).isNotEmpty();
    assertThat(runs.get(1).getProposed()).containsExactlyElementsOf(runs.get(0).getProposed());
    assertThat(runs.get(1).getCursorAfter().getResumeCursor()).isNull();
  }

  @Test
  void aPassWithoutAPlaceOfItsOwnStartsThePlanAgain() {
    assertThat(
            GitHubSdkCollector.startOf(ScoutCursor.candidatesHeldBack(null, null).getOwnResume()))
        .isZero();
    assertThat(GitHubSdkCollector.startOf("not a number")).isZero();
    assertThat(GitHubSdkCollector.startOf("7")).isEqualTo(7);
    assertThat(GitHubSdkCollector.startOf(null)).isZero();
  }

  @Test
  void waitsForItsIntervalBetweenPasses() {
    String anHourAgo = SourceFixtures.CLOCK.instant().minus(Duration.ofHours(1)).toString();

    CollectedBatch batch =
        collector(Duration.ofDays(7), plan(List.of("llm"), List.of()), 5)
            .collect(anHourAgo, null, 1000);

    assertThat(batch.getNotDue()).isTrue();
    assertThat(server.requests).isEmpty();
  }
}
