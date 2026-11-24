package ai.searchavo.sources.liveweb.scout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.platform.quota.QuotaCounter;
import ai.searchavo.platform.quota.QuotaKeys;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.CollectedBatch;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ScoutLiveWebSearchTest {

  static final String POLICY = "collector-under-test";

  final FixtureServer server = new FixtureServer();
  final DiscoveryCollectorTest.MapCounter counter = new DiscoveryCollectorTest.MapCounter();
  final QuotaKeys keys = new QuotaKeys("", SourceFixtures.CLOCK);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @AfterEach
  void stop() {
    server.close();
  }

  ScoutLiveWebSearch search(String depth, Resilience resilience) {
    SourceSettings settings =
        new SourceSettings(
            true, server.baseUrl(), "tvly-test", Duration.ofSeconds(2), Duration.ZERO, null);
    return new ScoutLiveWebSearch(
        HttpClients.client(
            TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP), settings),
        SourceFixtures.CLOCK,
        resilience,
        new ScoutLiveWebCredits(counter, keys, () -> 40L, meters).forFeature("scout-under-test"),
        POLICY,
        depth);
  }

  static Map<String, Object> body() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", "new AI tool launched");
    body.put("max_results", 5);
    return body;
  }

  QuotaCounter.Counter scoutCredits() {
    return new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS);
  }

  QuotaCounter.Counter globalCredits() {
    return new QuotaCounter.Counter(keys.global(), QuotaKeys.CREDITS);
  }

  @Test
  void aSearchPostsItsBodyToSearchAndGivesBackTheAnswer() {
    server.route(
        "/search", FixtureServer.Reply.ok("{\"usage\":{\"credits\":1},\"results\":[{\"n\":1}]}"));

    JsonNode root = search("basic", Resilience.NONE).search(body());

    assertThat(root.path("results")).hasSize(1);
    assertThat(server.requests).containsExactly("/search");
    assertThat(server.requestBodies)
        .containsExactly("{\"query\":\"new AI tool launched\",\"max_results\":5}");
  }

  @Test
  void aSearchCountsTheCreditsOfItsAnswerOnBothCountersUnderItsFeature() {
    server.route("/search", FixtureServer.Reply.ok("{\"usage\":{\"credits\":3},\"results\":[]}"));

    search("basic", Resilience.NONE).search(body());

    assertThat(counter.counts).containsEntry(globalCredits(), 3L).containsEntry(scoutCredits(), 3L);
    assertThat(
            meters
                .get("searchavo.live-web.credits")
                .tag("feature", "scout-under-test")
                .counter()
                .count())
        .isEqualTo(3.0);
  }

  // UC-028 BR-002: the limit applied now stops the search, and a raised limit opens it again.
  @Test
  void theCapIsReadAtEachSearchSoAChangeAtRuntimeApplies() {
    AtomicLong limit = new AtomicLong(0);
    ScoutLiveWebCredits credits = new ScoutLiveWebCredits(counter, keys, limit::get, meters);
    assertThat(credits.capReached()).as("0 turns the Scout's searches off").isTrue();

    limit.set(3_000);
    counter.counts.put(scoutCredits(), 2_999L);
    assertThat(credits.capReached()).isFalse();
    assertThat(credits.dailyCap()).isEqualTo(3_000);

    limit.set(2_999);
    assertThat(credits.capReached()).isTrue();
  }

  @Test
  void anAnswerWithoutUsageCostsWhatItsDepthCosts() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));

    search("advanced", Resilience.NONE).search(body());

    assertThat(counter.counts).containsEntry(scoutCredits(), 2L);
  }

  @Test
  void theCallGoesThroughItsResiliencePolicyAndEveryAttemptCountsItsCredits() {
    server.route("/search", FixtureServer.Reply.ok("{\"usage\":{\"credits\":1},\"results\":[]}"));
    List<String> policies = new ArrayList<>();
    Resilience triesTwice =
        new Resilience() {
          @Override
          public <T> T call(String policy, Supplier<T> work) {
            policies.add(policy);
            work.get();
            return work.get();
          }

          @Override
          public void require(String policy) {}
        };

    search("basic", triesTwice).search(body());

    assertThat(policies).containsExactly(POLICY);
    assertThat(server.requests).hasSize(2);
    assertThat(counter.counts).containsEntry(scoutCredits(), 2L);
  }

  @Test
  void aFailedAttemptCountsWhatItsDepthCostsAndFails() {
    server.route("/search", new FixtureServer.Reply(500, "{}", Map.of(), 0));

    assertThatThrownBy(() -> search("basic", Resilience.NONE).search(body()))
        .isInstanceOf(RuntimeException.class);

    assertThat(counter.counts).containsEntry(scoutCredits(), 1L);
  }

  @Test
  void aCallRefusedByItsPolicyMakesNoRequestAndCountsNoCredit() {
    assertThatThrownBy(() -> search("basic", LiveWebNewsCollectorTest.REFUSES).search(body()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(server.requests).isEmpty();
    assertThat(counter.counts).isEmpty();
  }

  @Test
  void aPostToAnotherPathCountsTheSameWay() {
    server.route("/extract", FixtureServer.Reply.ok("{\"usage\":{\"credits\":2},\"results\":[]}"));

    search("basic", Resilience.NONE).post("/extract", body(), attributes -> {});

    assertThat(server.requests).containsExactly("/extract");
    assertThat(counter.counts).containsEntry(scoutCredits(), 2L);
  }

  @Test
  void theCapIsReachedOnceTheScoutCounterIsAtTheDailyCap() {
    ScoutLiveWebSearch search = search("basic", Resilience.NONE);
    assertThat(search.capReached()).isFalse();

    counter.counts.put(scoutCredits(), 40L);

    assertThat(search.capReached()).isTrue();
  }

  @Test
  void aSearchAtTheDailyCapIsRefusedBeforeAnyRequestAndCountsNoCredit() {
    server.route("/search", FixtureServer.Reply.ok("{\"usage\":{\"credits\":1},\"results\":[]}"));
    counter.counts.put(scoutCredits(), 40L);
    ScoutLiveWebSearch search = search("basic", Resilience.NONE);

    assertThatThrownBy(() -> search.search(body()))
        .isInstanceOf(ScoutLiveWebSearch.CapReached.class)
        .hasMessage(
            "Daily live web credit cap of the Scout reached (40 credits); next try tomorrow");

    assertThat(server.requests).isEmpty();
    assertThat(counter.counts).containsOnly(Map.entry(scoutCredits(), 40L));
    assertThat(meters.find("searchavo.live-web.credits").counter()).isNull();
  }

  @Test
  void aPostToAnotherPathAtTheDailyCapIsRefusedTheSameWay() {
    server.route("/extract", FixtureServer.Reply.ok("{\"usage\":{\"credits\":2},\"results\":[]}"));
    counter.counts.put(scoutCredits(), 40L);
    ScoutLiveWebSearch search = search("basic", Resilience.NONE);

    assertThatThrownBy(() -> search.post("/extract", body(), attributes -> {}))
        .isInstanceOf(ScoutLiveWebSearch.CapReached.class);

    assertThat(server.requests).isEmpty();
    assertThat(counter.counts).containsOnly(Map.entry(scoutCredits(), 40L));
  }

  @Test
  void aSearchAtTheDailyCapDoesNotReachItsResiliencePolicy() {
    counter.counts.put(scoutCredits(), 40L);
    ScoutLiveWebSearch search = search("basic", LiveWebNewsCollectorTest.REFUSES);

    assertThatThrownBy(() -> search.search(body()))
        .isInstanceOf(ScoutLiveWebSearch.CapReached.class);
  }

  @Test
  void theDeferredBatchKeepsThePassOpenAndSaysTheCap() {
    CollectedBatch batch = search("basic", Resilience.NONE).deferred();

    assertThat(batch.getDeferred()).isTrue();
    assertThat(batch.getSkipReason())
        .isEqualTo(
            "Daily live web credit cap of the Scout reached (40 credits); next try tomorrow");
  }
}
