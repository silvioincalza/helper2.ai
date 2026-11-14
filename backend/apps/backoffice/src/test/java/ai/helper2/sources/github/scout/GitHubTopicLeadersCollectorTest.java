package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateSignals;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.RunStatus;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.scout.admission.AdmissionFixtures;
import ai.helper2.scout.usecase.CappedReview;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.github.scout.GitHubTopicLeadersCollector.Indexing;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubTopicLeadersCollectorTest {

  static final String TWO_REPOS =
      """
      {"total_count":2,"items":[
        {"full_name":"langchain-ai/langgraph","html_url":"https://github.com/langchain-ai/langgraph",
         "description":"Build resilient language agents as graphs.","stargazers_count":21000,
         "topics":["agents","llm","langchain"]},
        {"full_name":"gunthercox/ChatterBot","html_url":"https://github.com/gunthercox/ChatterBot",
         "description":"ChatterBot is a machine learning, conversational dialog engine.",
         "stargazers_count":14000,"topics":["chatbot"]}
      ]}\
      """;

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  static final Instant NOW = SourceFixtures.CLOCK.instant();

  static final Indexing ACTIVE_WITH_1000_STARS =
      new Indexing(1000, Duration.ofDays(90), 1000, Duration.ZERO);

  static String repo(String fullName, long stars, Instant pushedAt) {
    return """
    {"full_name":"%s","html_url":"https://github.com/%s","description":"A library for LLM agents.",
     "stargazers_count":%d,"pushed_at":"%s","topics":["llm"]}\
    """
        .formatted(fullName, fullName, stars, pushedAt);
  }

  static String found(List<String> repos) {
    return "{\"total_count\":%d,\"items\":[%s]}".formatted(repos.size(), String.join(",", repos));
  }

  GitHubTopicLeadersCollector collector(Duration interval, List<String> topics) {
    return collector(interval, topics, Indexing.OFF);
  }

  GitHubTopicLeadersCollector collector(Duration interval, List<String> topics, Indexing indexing) {
    return new GitHubTopicLeadersCollector(
        new SourceSettings(true, server.baseUrl(), "token", Duration.ofSeconds(2), Duration.ZERO),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(interval),
        new GitHubTopicLeadersCollector.Limits(topics, 3000, 50, 25, indexing),
        GitHubSdkCollector.Pause.NONE);
  }

  List<String> requests() {
    return server.requests.stream().map(r -> URLDecoder.decode(r, StandardCharsets.UTF_8)).toList();
  }

  @Test
  void anActiveRepoWithEnoughStarsIsIndexedAndEveryLeaderIsProposedForTheReviewListToSort() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(
            found(
                List.of(
                    repo("pytorch/pytorch", 90_000, NOW.minus(Duration.ofDays(2))),
                    repo("gunthercox/ChatterBot", 14_000, NOW.minus(Duration.ofDays(400)))))));

    CollectedBatch batch =
        collector(Duration.ZERO, List.of("llm"), ACTIVE_WITH_1000_STARS).collect(null, null, 1000);

    assertThat(AdmissionFixtures.indexedOf(batch)).hasSize(1);
    SourceHit pytorch = AdmissionFixtures.indexedOf(batch).getFirst();
    assertThat(pytorch.getSourceId()).isEqualTo("github-topic-leaders");
    assertThat(pytorch.getTitle()).isEqualTo("pytorch/pytorch");
    assertThat(pytorch.getCanonicalUrl().getValue())
        .isEqualTo("https://github.com/pytorch/pytorch");
    assertThat(pytorch.getPopularity()).extracting(p -> p.getValue()).containsExactly(90_000L);
    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue())
        .containsExactly(
            "https://github.com/pytorch/pytorch", "https://github.com/gunthercox/chatterbot");
    assertThat(batch.getNextCursor()).isNull();
    assertThat(batch.getCheckpoint()).isNotNull();
  }

  @Test
  void anActiveLeaderThePerTopicLimitLeftOutIsStillProposedForReview() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(
            found(List.of(repo("first/leader", 90_000, NOW), repo("second/leader", 80_000, NOW)))));
    Indexing topOne = new Indexing(1000, Duration.ofDays(90), 1, Duration.ZERO);

    CollectedBatch batch =
        collector(Duration.ZERO, List.of("llm"), topOne).collect(null, null, 1000);

    assertThat(batch.getHits()).extracting(SourceHit::getTitle).containsExactly("first/leader");
    assertThat(batch.getCandidates()).extracting(Candidate::getTitle).contains("second/leader");
  }

  @Test
  void asksForActiveReposAboveTheIndexFloorAHundredAPage() {
    server.route("/search/repositories", FixtureServer.Reply.ok(found(List.of())));

    collector(Duration.ZERO, List.of("rag"), ACTIVE_WITH_1000_STARS).collect(null, null, 1000);

    String since = LocalDate.ofInstant(NOW.minus(Duration.ofDays(90)), ZoneOffset.UTC).toString();
    assertThat(requests().getFirst())
        .contains("q=topic:rag stars:>=1000 pushed:>=" + since + " fork:false archived:false")
        .contains("sort=stars")
        .contains("per_page=100")
        .contains("page=1");
  }

  @Test
  void aRepoTheSearchReturnsAgainstTheRuleIsNotIndexed() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(
            found(
                List.of(
                    repo("few/stars", 999, NOW.minus(Duration.ofDays(1))),
                    repo("long/quiet", 5_000, NOW.minus(Duration.ofDays(91))),
                    """
                    {"full_name":"no/date","html_url":"https://github.com/no/date",
                     "description":"A library for LLM agents.","stargazers_count":5000}\
                    """))));

    CollectedBatch batch =
        collector(Duration.ZERO, List.of("llm"), ACTIVE_WITH_1000_STARS).collect(null, null, 1000);

    assertThat(batch.getHits()).hasSize(3);
    assertThat(AdmissionFixtures.indexedOf(batch)).isEmpty();
  }

  static String chatbotRepo(String fullName, String description, String otherTopic) {
    return """
    {"full_name":"%s","html_url":"https://github.com/%s","description":"%s",
     "stargazers_count":29576,"pushed_at":"%s","topics":["chatbot","%s"]}\
    """
        .formatted(fullName, fullName, description, NOW.minus(Duration.ofDays(1)), otherTopic);
  }

  @Test
  void aLeaderIsIndexedOnlyWhenSomethingBesideItsTopicSaysItIsAboutAi() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(
            found(
                List.of(
                    chatbotRepo("acme/telegram-bot", "A wrapper for the Telegram Bot API", "bot"),
                    chatbotRepo("acme/by-its-words", "A chat app on top of an LLM", "bot"),
                    chatbotRepo("acme/by-another-topic", "Talk to your documents", "rag")))));

    CollectedBatch batch =
        collector(Duration.ZERO, List.of("chatbot"), ACTIVE_WITH_1000_STARS)
            .collect(null, null, 1000);

    assertThat(batch.getHits())
        .extracting(h -> h.getExternalId())
        .containsExactly("acme/by-its-words", "acme/by-another-topic");
    assertThat(batch.getCandidates())
        .as("the review list still gets the one that was not indexed")
        .extracting(c -> c.getTitle())
        .contains("acme/telegram-bot");
  }

  @Test
  void aRepoLeadingSeveralTopicsIsIndexedOnce() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(found(List.of(repo("pytorch/pytorch", 90_000, NOW)))));

    CollectedBatch batch =
        collector(Duration.ZERO, List.of("llm", "deep-learning"), ACTIVE_WITH_1000_STARS)
            .collect(null, null, 1000);

    assertThat(batch.getHits()).hasSize(1);
  }

  @Test
  void stopsAtTheRunsItemBudgetAndTheNextRunReadsTheNextPage() {
    List<String> fullPage = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      fullPage.add(repo("owner/repo-" + i, 2_000, NOW));
    }
    server.route("/search/repositories", FixtureServer.Reply.ok(found(fullPage)));
    GitHubTopicLeadersCollector collector =
        collector(Duration.ZERO, List.of("llm"), ACTIVE_WITH_1000_STARS);

    CollectedBatch first = collector.collect(null, null, 100);

    assertThat(first.getHits()).hasSize(100);
    assertThat(first.getNextCursor()).isEqualTo("0:2");
    assertThat(first.getCheckpoint()).isNull();
    assertThat(first.getCandidates()).isEmpty();
    assertThat(server.requests).hasSize(1);

    collector.collect(null, first.getNextCursor(), 100);

    assertThat(requests().get(1)).contains("page=2");
  }

  @Test
  void aRunNeverIndexesMoreThanItsItemBudgetAndTheNextRunTakesTheRestOfThePage() {
    List<String> fullPage = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      fullPage.add(repo("owner/repo-" + i, 2_000, NOW));
    }
    server.route("/search/repositories", FixtureServer.Reply.ok(found(fullPage)));
    Indexing onePage = new Indexing(1000, Duration.ofDays(90), 100, Duration.ZERO);
    GitHubTopicLeadersCollector collector = collector(Duration.ZERO, List.of("llm"), onePage);

    CollectedBatch first = collector.collect(null, null, 30);

    assertThat(first.getHits()).hasSize(30);
    assertThat(first.getNextCursor()).isEqualTo("0:1:30");
    assertThat(first.getCheckpoint()).isNull();

    CollectedBatch second = collector.collect(null, first.getNextCursor(), 1000);

    assertThat(requests().get(1)).contains("page=1");
    assertThat(second.getHits()).hasSize(70);
    assertThat(second.getHits().getFirst().getTitle()).isEqualTo("owner/repo-30");
    assertThat(second.getNextCursor()).isNull();
  }

  @Test
  void aTopicGivesNoMoreReposThanTheRuleAllowsEvenInsideAPage() {
    List<String> fullPage = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      fullPage.add(repo("owner/repo-" + i, 2_000, NOW));
    }
    server.route("/search/repositories", FixtureServer.Reply.ok(found(fullPage)));
    Indexing topOne = new Indexing(1000, Duration.ofDays(90), 1, Duration.ZERO);

    CollectedBatch batch =
        collector(Duration.ZERO, List.of("llm"), topOne).collect(null, null, 1000);

    assertThat(batch.getHits()).extracting(SourceHit::getTitle).containsExactly("owner/repo-0");
    assertThat(requests()).filteredOn(r -> r.contains("pushed:>=")).hasSize(1);
    assertThat(batch.getNextCursor()).isNull();
  }

  @Test
  void aCursorThatIsNotAPlaceStartsThePassAgain() {
    assertThat(GitHubTopicLeadersCollector.Place.of("nonsense"))
        .isEqualTo(GitHubTopicLeadersCollector.Place.START);
    assertThat(GitHubTopicLeadersCollector.Place.of("1:x"))
        .isEqualTo(GitHubTopicLeadersCollector.Place.START);
    assertThat(GitHubTopicLeadersCollector.Place.of("2:3:40").cursor()).isEqualTo("2:3:40");
    assertThat(GitHubTopicLeadersCollector.Place.of("2:3").cursor()).isEqualTo("2:3");
  }

  @Test
  void readsAtMostTheReposPerTopicOfTheRule() {
    List<String> fullPage = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      fullPage.add(repo("owner/repo-" + i, 2_000, NOW));
    }
    server.route("/search/repositories", FixtureServer.Reply.ok(found(fullPage)));
    Indexing twoPages = new Indexing(1000, Duration.ofDays(90), 200, Duration.ZERO);

    CollectedBatch batch =
        collector(Duration.ZERO, List.of("llm"), twoPages).collect(null, null, 10_000);

    assertThat(requests()).filteredOn(r -> r.contains("pushed:>=")).hasSize(2);
    assertThat(batch.getNextCursor()).isNull();
  }

  @Test
  void afterARunTheReviewCapHeldBackTheNextRunProposesTheHeldCandidates() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(found(List.of(repo("pytorch/pytorch", 90_000, NOW)))));

    List<CappedReview.Run> runs =
        CappedReview.aHeldBackRunThenTheNext(
            collector(Duration.ofDays(7), List.of("llm"), ACTIVE_WITH_1000_STARS),
            SourceFixtures.CLOCK);

    assertThat(runs).extracting(CappedReview.Run::getStatus).containsOnly(RunStatus.SUCCEEDED);
    assertThat(runs.get(0).getCursorAfter().getCandidatesHeldBack()).isTrue();
    assertThat(runs.get(0).getProposed()).isNotEmpty();
    assertThat(runs.get(1).getProposed()).containsExactlyElementsOf(runs.get(0).getProposed());
    assertThat(runs.get(1).getCursorAfter().getResumeCursor()).isNull();
  }

  @Test
  void aPassTheReviewCapHeldBackAsksOnlyForTheReviewListAgain() {
    server.route(
        "/search/repositories",
        FixtureServer.Reply.ok(found(List.of(repo("pytorch/pytorch", 90_000, NOW)))));

    CollectedBatch batch =
        collector(Duration.ofDays(7), List.of("llm"), ACTIVE_WITH_1000_STARS)
            .collect(ScoutCursor.candidatesHeldBack(null, null), 1000);

    assertThat(batch.getHits()).isEmpty();
    assertThat(requests()).hasSize(1).noneMatch(r -> r.contains("pushed:>="));
  }

  @Test
  void proposesTheMostStarredReposOfEachTopicForReviewAndIndexesNothing() {
    server.route("/search/repositories", FixtureServer.Reply.ok(TWO_REPOS));

    CollectedBatch batch = collector(Duration.ZERO, List.of("agents")).collect(null, null, 1000);

    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue())
        .containsExactly(
            "https://github.com/langchain-ai/langgraph",
            "https://github.com/gunthercox/chatterbot");
    Candidate langgraph = batch.getCandidates().getFirst();
    assertThat(langgraph.getTitle()).isEqualTo("langchain-ai/langgraph");
    assertThat(langgraph.getSnippet()).isEqualTo("Build resilient language agents as graphs.");
    assertThat(langgraph.getType()).isNotEqualTo(ResourceType.COURSE);
    assertThat(langgraph.getFacts()).doesNotContainKey("stars");
    assertThat(langgraph.getSignals()).isEqualTo(new CandidateSignals(21000L));
    assertThat(batch.getNewCandidatesPerDay()).isEqualTo(25);
    assertThat(batch.getCheckpoint()).isNotNull();
  }

  @Test
  void aLeaderCarriesTheLastPushArchivedAndForkGitHubStates() {
    String stated =
        """
        {"total_count":1,"items":[
          {"full_name":"langchain-ai/langgraph","html_url":"https://github.com/langchain-ai/langgraph",
           "description":"Build resilient language agents as graphs.","stargazers_count":21000,
           "pushed_at":"2026-11-01T08:30:00Z","archived":false,"fork":true,"topics":["agents","llm"]}
        ]}\
        """;
    server.route("/search/repositories", FixtureServer.Reply.ok(stated));

    CollectedBatch batch = collector(Duration.ZERO, List.of("agents")).collect(null, null, 1000);

    assertThat(batch.getCandidates().getFirst().getSignals())
        .isEqualTo(
            new CandidateSignals(21000L, Instant.parse("2026-11-01T08:30:00Z"), false, true));
  }

  @Test
  void asksForPopularActiveOriginalReposMostStarredFirst() {
    server.route("/search/repositories", FixtureServer.Reply.ok(TWO_REPOS));

    collector(Duration.ZERO, List.of("rag")).collect(null, null, 1000);

    String request = URLDecoder.decode(server.requests.getFirst(), StandardCharsets.UTF_8);
    assertThat(request)
        .contains("q=topic:rag stars:>=3000 fork:false archived:false")
        .contains("sort=stars")
        .contains("order=desc")
        .contains("per_page=50")
        .contains("page=1");
  }

  @Test
  void aRepoLeadingSeveralTopicsIsOneCandidateTaggedWithEach() {
    server.route("/search/repositories", FixtureServer.Reply.ok(TWO_REPOS));

    CollectedBatch batch =
        collector(Duration.ZERO, List.of("agents", "llm")).collect(null, null, 1000);

    assertThat(batch.getCandidates()).hasSize(2);
    assertThat(batch.getCandidates().getFirst().getTags()).containsExactly("agents", "llm");
    assertThat(server.requests).hasSize(2);
  }

  @Test
  void waitsForItsIntervalBetweenPasses() {
    String anHourAgo = SourceFixtures.CLOCK.instant().minus(Duration.ofHours(1)).toString();

    CollectedBatch batch =
        collector(Duration.ofDays(7), List.of("agents")).collect(anHourAgo, null, 1000);

    assertThat(batch.getNotDue()).isTrue();
    assertThat(server.requests).isEmpty();
  }
}
