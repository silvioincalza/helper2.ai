package ai.helper2.sources.liveweb.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.WebPageTitle;
import ai.helper2.shared.WebSources;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.TavilySource;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LiveWebNewsCollectorTest {

  static final String RESULTS =
      """
      {"results":[
        {"url":"https://news.example/acme-launches-agent","title":"Acme launches an open-source AI agent framework",
         "content":"Acme released Forge, an open-source agent framework for building LLM agents.","published_date":"2026-10-21T08:00:00Z"},
        {"url":"https://news.example/football","title":"Local team wins the cup","content":"A football match report."}
      ]}\
      """;

  static final Resilience TRIES_TWICE =
      new Resilience() {
        @Override
        public <T> T call(String policy, Supplier<T> work) {
          work.get();
          return work.get();
        }

        @Override
        public void require(String policy) {}
      };

  static final Resilience REFUSES =
      new Resilience() {
        @Override
        public <T> T call(String policy, Supplier<T> work) {
          throw new IllegalStateException("circuit open");
        }

        @Override
        public void require(String policy) {}
      };

  final FixtureServer server = new FixtureServer();
  final DiscoveryCollectorTest.MapCounter counter = new DiscoveryCollectorTest.MapCounter();
  final QuotaKeys keys = new QuotaKeys("", SourceFixtures.CLOCK);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @AfterEach
  void stop() {
    server.close();
  }

  LiveWebNewsCollector collector(String token, List<String> queries) {
    return collector(token, queries, Resilience.NONE);
  }

  LiveWebNewsCollector collector(String token, List<String> queries, Resilience resilience) {
    return new LiveWebNewsCollector(
        new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO),
        TestHttp.restClientBuilder(
            Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        resilience,
        new RunInterval(Duration.ofDays(5)),
        queries,
        new ScoutLiveWebCredits(counter, keys, 40, meters));
  }

  @Test
  void eachSearchCountsItsCreditsOnTheScoutCapUnderItsOwnFeature() {
    server.route("/search", FixtureServer.Reply.ok("{\"usage\":{\"credits\":1},\"results\":[]}"));
    collector("tvly-test", List.of("new AI tool launched", "new MCP server released"))
        .collect(null, null, 500);

    assertThat(counter.counts)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.CREDITS), 2L)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 2L);
    assertThat(
            meters.get("helper2.live-web.credits").tag("feature", "scout-news").counter().count())
        .isEqualTo(2.0);
  }

  @Test
  void pastTheScoutsDailyCapItSearchesNothingAndKeepsThePassOpen() {
    counter.counts.put(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 40L);
    CollectedBatch batch =
        collector("tvly-test", List.of("new AI tool launched")).collect(null, null, 500);

    assertThat(batch.getDeferred()).isTrue();
    assertThat(batch.getSkipReason()).contains("credit cap");
    assertThat(server.requests).isEmpty();
  }

  @Test
  void aCapReachedBetweenTwoQueriesKeepsTheHitsAndLeavesThePassOpenAtTheQueryNotSearched() {
    counter.counts.put(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 39L);
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch =
        collector("tvly-test", List.of("new AI tool launched", "new MCP server released"))
            .collect("2026-10-15T00:00:00Z", null, 500);

    assertThat(server.requests).hasSize(1);
    assertThat(batch.getHits()).isNotEmpty();
    assertThat(batch.getCheckpoint()).as("the pass is not complete").isNull();
    assertThat(batch.getNextCursor())
        .isEqualTo(QueryProgress.cursor(List.of("new AI tool launched")));
  }

  @Test
  void aPassLeftOpenSearchesOnlyTheQueriesNotDoneOverTheSameDaysAndThenMovesItsCheckpoint() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
    CollectedBatch batch =
        collector("tvly-test", List.of("new AI tool launched", "new MCP server released"))
            .collect(
                "2026-10-15T00:00:00Z", QueryProgress.cursor(List.of("new AI tool launched")), 500);

    assertThat(server.requestBodies)
        .singleElement()
        .asString()
        .contains("new MCP server released")
        .contains("\"start_date\":\"2026-10-15\"");
    assertThat(batch.getNextCursor()).isNull();
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  @Test
  void everyHttpAttemptOfARetriedSearchCountsItsCredits() {
    server.route("/search", FixtureServer.Reply.ok("{\"usage\":{\"credits\":1},\"results\":[]}"));
    collector("tvly-test", List.of("new AI tool launched"), TRIES_TWICE).collect(null, null, 500);

    assertThat(counter.counts)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 2L);
  }

  @Test
  void aSearchRefusedBeforeAnyHttpCallCountsNoCredit() {
    assertThatThrownBy(() ->
            collector("tvly-test", List.of("new AI tool launched"), REFUSES)
                .collect(null, null, 500))
        .isInstanceOf(IllegalStateException.class);

    assertThat(counter.counts).isEmpty();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void firstRunAsksForTheLastWeekOncePerQueryAndKeepsOnlyAiResultsDeduplicated() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch =
        collector("tvly-test", List.of("new AI tool launched", "new MCP server released"))
            .collect(null, null, 500);

    assertThat(server.requestBodies)
        .hasSize(2)
        .allSatisfy(body ->
            assertThat(body)
                .contains("\"topic\":\"news\"")
                .contains("\"search_depth\":\"basic\"")
                .contains("\"time_range\":\"week\"")
                .doesNotContain("start_date"));
    assertThat(batch.getHits())
        .extracting(SourceHit::getExternalId)
        .containsExactly("https://news.example/acme-launches-agent");
    SourceHit hit = batch.getHits().getFirst();
    assertThat(hit.getSourceId()).isEqualTo("live-web-scout");
    assertThat(hit.getType()).isEqualTo(ResourceType.NEWS);
    assertThat(hit.getDescription()).contains("Forge");
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  @Test
  void laterRunsAskOnlyForResultsPublishedSinceThePreviousPass() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
    collector("tvly-test", List.of("new AI tool launched"))
        .collect("2026-10-15T09:00:00Z", null, 500);
    assertThat(server.requestBodies.getFirst())
        .contains("\"start_date\":\"2026-10-15\"")
        .doesNotContain("time_range");
  }

  @Test
  void spendsNoCreditsBeforeTheFiveDayIntervalHasPassed() {
    CollectedBatch batch =
        collector("tvly-test", List.of("new AI tool launched"))
            .collect("2026-10-20T09:00:00Z", null, 500);
    assertThat(batch.getNotDue()).isTrue();
    assertThat(server.requests).isEmpty();
  }

  @Test
  void isDisabledWithoutAnApiKeyOrQueries() {
    assertThat(collector("", List.of("q")).getDescriptor().getEnabled()).isFalse();
    assertThat(collector("tvly-test", List.of()).getDescriptor().getEnabled()).isFalse();
  }

  @Test
  void theTavilySourcesAreTheOnesWhoseTitlesAreCleanedOnRead() {
    TavilySource live =
        new TavilySource(
            new SourceSettings(true, server.baseUrl(), "t", Duration.ofSeconds(2), Duration.ZERO),
            TestHttp.restClientBuilder(
                Duration.ofSeconds(2), io.micrometer.observation.ObservationRegistry.NOOP),
            SourceFixtures.CLOCK);
    assertThat(WebSources.FIXED)
        .containsExactlyInAnyOrder(
            live.getDescriptor().getId(),
            LiveWebNewsCollector.ID,
            CourseCollector.ID,
            LinkedInCollector.ID,
            LiveWebEventsCollector.ID);
    assertThat(WebSources.isWeb("discovery-coding")).isTrue();
    assertThat(WebSources.isWeb("discovery-")).isFalse();
    assertThat(WebSources.isWeb("github")).isFalse();
    assertThat(WebSources.isWeb(null)).isFalse();
    assertThat(
            WebPageTitle.forSource(
                "discovery-coding",
                "Cursor | The AI Code Editor",
                CanonicalUrl.of("https://cursor.com")))
        .isEqualTo(WebPageTitle.of("Cursor | The AI Code Editor", "cursor.com").getTitle());
  }
}
