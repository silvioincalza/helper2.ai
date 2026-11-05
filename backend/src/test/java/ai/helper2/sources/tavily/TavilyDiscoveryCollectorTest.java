package ai.helper2.sources.tavily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TavilyDiscoveryCollectorTest {

  static final DiscoveryCategory SELF_HOSTED =
      category(
          "self-hosted",
          ResourceType.PRODUCT,
          List.of("open-source self-hosted AI app"),
          List.of("self-hosted", "self hosted"));
  static final DiscoveryCategory LOCAL_AI =
      category(
          "local-ai",
          ResourceType.PRODUCT,
          List.of("desktop app to run LLMs locally"),
          List.of("offline", "run locally"));

  static final String RESULTS =
      """
      {"request_id":"req-1","usage":{"credits":2},"results":[
        {"url":"https://www.example-lists.com/blog/12-best-self-hosted-llm-apps","title":"12 Best Self-Hosted LLM Apps in 2026",
         "content":"We compare Open WebUI, LibreChat and AnythingLLM for running AI models offline at home.","score":0.91,
         "published_date":"2026-10-20","favicon":"https://www.example-lists.com/favicon.ico"},
        {"url":"https://openwebui.com/","title":"Open WebUI","content":"Self-hosted AI platform for LLMs that runs offline.","score":0.88},
        {"url":"https://anythingllm.com/pricing","title":"Pricing | AnythingLLM","content":"Run any LLM privately on your desktop.","score":0.80},
        {"url":"https://github.com/danny-avila/LibreChat","title":"GitHub - danny-avila/LibreChat: an open-source alternative to ChatGPT","content":"Open-source AI chat for LLM providers.","score":0.77},
        {"url":"https://github.com/topics/llm","title":"llm · GitHub Topics","content":"LLM repositories.","score":0.70},
        {"url":"https://somecompany.ai/blog/why-we-built-an-agent","title":"Why we built an AI agent","content":"A story about our LLM agent.","score":0.75},
        {"url":"https://blog.somecompany.ai/","title":"Somecompany blog","content":"Posts about our LLM agent.","score":0.75},
        {"url":"https://newsletter.ai/how-we-built-our-ai-stack","title":"How we built our AI stack","content":"Our LLM tools.","score":0.75},
        {"url":"https://medium.com/@someone","title":"My AI agent","content":"An LLM agent I wrote.","score":0.74},
        {"url":"https://gardening.example/","title":"Gardening tools","content":"Spades and rakes.","score":0.90},
        {"url":"https://lowscore.ai/","title":"Low score AI","content":"An LLM product.","score":0.10}
      ]}\
      """;

  final FixtureServer server = new FixtureServer();
  final MapCounter counter = new MapCounter();
  final QuotaKeys keys = new QuotaKeys("", SourceFixtures.CLOCK);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @AfterEach
  void stop() {
    server.close();
  }

  static DiscoveryCategory category(
      String id, ResourceType type, List<String> queries, List<String> words) {
    return options(id, type, queries, words, 0.4, false, true, true);
  }

  static DiscoveryCategory options(
      String id,
      ResourceType type,
      List<String> queries,
      List<String> words,
      double minScore,
      boolean requireKeyword,
      boolean indexLists,
      boolean enabled) {
    return new DiscoveryCategory(
        id,
        DiscoveryFamily.RUN_IT_YOURSELF,
        "Short",
        "Name of " + id,
        type,
        queries,
        DiscoveryCategory.Keywords.of(words),
        Duration.ofDays(7),
        enabled,
        minScore,
        requireKeyword,
        indexLists,
        List.of("youtube.com"));
  }

  /** The collector of the first of [all]; it is due on the fixtures' day (its first day is 0). */
  TavilyDiscoveryCollector collector(String token, long cap, DiscoveryCategory... all) {
    return new TavilyDiscoveryCollector(
        new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO),
        ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new TavilyDiscoveryCollector.Search("advanced", 10),
        all[0],
        List.of(all),
        new ScoutTavilyCredits(counter, keys, cap, meters));
  }

  TavilyDiscoveryCollector collector(DiscoveryCategory... all) {
    return collector("tvly-test", 40, all);
  }

  @Test
  void searchesEachQueryOnTheGeneralWebWithItsMetadataButNoPageText() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
    DiscoveryCategory twoQueries =
        category(
            "health", ResourceType.PRODUCT, List.of("medical scribe", "health app"), List.of());
    collector(twoQueries).collect(null, null, 1000);

    assertThat(server.requestBodies).hasSize(2);
    assertThat(server.requestBodies.get(0))
        .contains("\"query\":\"medical scribe\"")
        .contains("\"topic\":\"general\"")
        .contains("\"search_depth\":\"advanced\"")
        .contains("\"max_results\":10")
        .contains("\"include_answer\":false")
        .contains("\"include_raw_content\":false")
        .contains("\"include_images\":false")
        .contains("\"include_published_date\":true")
        .contains("\"include_favicon\":true")
        .contains("\"include_usage\":true")
        .contains("\"exclude_domains\":[\"youtube.com\"]")
        .contains("\"time_range\":\"month\"")
        .doesNotContain("start_date");
    assertThat(server.requestBodies.get(1)).contains("\"query\":\"health app\"");
  }

  @Test
  void aLaterPassAsksOnlyForPagesSinceThePreviousOne() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
    collector(SELF_HOSTED).collect("2026-10-01T03:07:00Z", null, 1000);

    assertThat(server.requestBodies.getFirst())
        .contains("\"start_date\":\"2026-10-01\"")
        .doesNotContain("time_range");
  }

  @Test
  void indexesListPagesAsArticlesAndProposesProductSites() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch = collector(SELF_HOSTED, LOCAL_AI).collect(null, null, 1000);

    assertThat(batch.getHits())
        .extracting(SourceHit::getCanonicalUrl)
        .extracting(CanonicalUrl::getValue)
        .containsExactly("https://example-lists.com/blog/12-best-self-hosted-llm-apps");
    assertThat(batch.getHits().getFirst().getType()).isEqualTo(ResourceType.ARTICLE);
    assertThat(batch.getHits().getFirst().getSourceId()).isEqualTo("discovery-self-hosted");

    // A home page, a pricing page (its site's home page instead) and a repository whose title says
    // "alternative". Dropped: the topics page, the blog post, the blog host, the post slug, the
    // publisher, the page not about AI, the low score.
    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue())
        .containsExactly(
            "https://openwebui.com",
            "https://anythingllm.com",
            "https://github.com/danny-avila/librechat");
    Candidate first = batch.getCandidates().getFirst();
    assertThat(first.getType()).isEqualTo(ResourceType.PRODUCT);
    assertThat(first.getSnippet()).contains("runs offline");
    assertThat(batch.getCandidates().get(1).getTitle()).isEqualTo("AnythingLLM");
    assertThat(batch.getCheckpoint()).isEqualTo(SourceFixtures.CLOCK.instant().toString());
  }

  @Test
  void aResultGetsItsCategoryAndEveryCategoryWhoseWordsItHas() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch = collector(SELF_HOSTED, LOCAL_AI).collect(null, null, 1000);

    Map<String, Candidate> byUrl =
        batch.getCandidates().stream()
            .collect(Collectors.toMap(c -> c.getUrl().getValue(), c -> c));
    assertThat(byUrl.get("https://openwebui.com").getTags())
        .containsExactlyInAnyOrder("self-hosted", "local-ai");
    assertThat(byUrl.get("https://openwebui.com").getPlatform()).isEqualTo("local-ai, self-hosted");
    assertThat(byUrl.get("https://github.com/danny-avila/librechat").getTags())
        .containsExactly("self-hosted");
    assertThat(batch.getHits().getFirst().getTags())
        .containsExactlyInAnyOrder("self-hosted", "local-ai");
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyKeptResultCarriesWhatTheSearchSaidAboutIt() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch = collector(SELF_HOSTED, LOCAL_AI).collect(null, null, 1000);

    Map<String, Object> list = batch.getHits().getFirst().getFacts();
    assertThat(list.get("categories")).isEqualTo(List.of("local-ai", "self-hosted"));
    Map<String, Object> discovery = (Map<String, Object>) list.get("discovery");
    assertThat(discovery)
        .containsEntry("category", "self-hosted")
        .containsEntry("query", "open-source self-hosted AI app")
        .containsEntry("rank", 0)
        .containsEntry("score", 0.91)
        .containsEntry("searchDepth", "advanced")
        .containsEntry("shape", "LIST")
        .containsEntry("publishedDate", "2026-10-20")
        .containsEntry("requestId", "req-1")
        .containsEntry("searchedAt", "2026-10-22T10:00:00Z")
        .containsEntry("favicon", "https://www.example-lists.com/favicon.ico")
        .containsEntry(
            "matchedWords",
            Map.of(
                "self-hosted", List.of("self-hosted", "self hosted"),
                "local-ai", List.of("offline")));
    Map<String, Object> product =
        (Map<String, Object>) batch.getCandidates().getFirst().getFacts().get("discovery");
    assertThat(product).containsEntry("shape", "PRODUCT").containsEntry("rank", 1);
    assertThat(product).doesNotContainKey("publishedDate");
  }

  @Test
  void aProductKeepsTheWholeExcerptAsEvidenceAndAListKeepsTheShortSnippet() {
    String longText = "Self-hosted AI platform. " + "x".repeat(3000);
    server.route(
        "/search",
        FixtureServer.Reply.ok(
            """
            {"results":[
              {"url":"https://openwebui.com/","title":"Open WebUI","content":"%s","score":0.9},
              {"url":"https://lists.example/top","title":"12 Best Self-Hosted AI Apps","content":"%s","score":0.9}
            ]}\
            """
                .formatted(longText, longText)));
    CollectedBatch batch = collector(SELF_HOSTED).collect(null, null, 1000);

    assertThat(batch.getCandidates().getFirst().getSnippet())
        .hasSize(TavilyDiscoveryCollector.MAX_EVIDENCE);
    assertThat(batch.getHits().getFirst().getDescription()).hasSizeLessThanOrEqualTo(400);
  }

  @Test
  void aCategoryThatRequiresItsWordsDropsResultsWithoutThemAndCanSkipLists() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    DiscoveryCategory strict =
        options(
            "local-ai",
            ResourceType.PRODUCT,
            List.of("q"),
            List.of("offline", "run any llm"),
            0.5,
            true,
            false,
            true);
    CollectedBatch batch = collector(strict).collect(null, null, 1000);

    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue())
        .containsExactly("https://openwebui.com", "https://anythingllm.com");
  }

  @Test
  void aCategoryScoreFloorDropsLowerResults() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    DiscoveryCategory picky =
        options(
            "self-hosted", ResourceType.PRODUCT, List.of("q"), List.of(), 0.85, false, true, true);
    CollectedBatch batch = collector(picky).collect(null, null, 1000);

    assertThat(batch.getCandidates())
        .extracting(c -> c.getUrl().getValue())
        .containsExactly("https://openwebui.com");
    assertThat(batch.getHits()).hasSize(1);
  }

  @Test
  void eachSearchCountsItsCreditsOnTheGlobalAndTheScoutCounters() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    collector(SELF_HOSTED).collect(null, null, 1000);

    assertThat(counter.counts)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.CREDITS), 2L)
        .containsEntry(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 2L);
    assertThat(
            meters
                .get("helper2.tavily.credits")
                .tag("feature", "scout-discovery")
                .counter()
                .count())
        .isEqualTo(2.0);
  }

  @Test
  void withoutUsageASearchCostsWhatItsDepthCosts() {
    assertThat(ScoutTavilyCredits.creditsOf(tree("{}"), "advanced")).isEqualTo(2);
    assertThat(ScoutTavilyCredits.creditsOf(tree("{}"), "basic")).isEqualTo(1);
    assertThat(ScoutTavilyCredits.creditsOf(tree("{\"usage\":{\"credits\":4}}"), "basic"))
        .isEqualTo(4);
  }

  @Test
  void pastTheDailyCapTheCollectorSkipsWithoutSearching() {
    counter.counts.put(new QuotaCounter.Counter(keys.global(), QuotaKeys.SCOUT_CREDITS), 40L);
    CollectedBatch batch = collector("tvly-test", 40, SELF_HOSTED).collect(null, null, 1000);

    assertThat(batch.getNotDue()).isTrue();
    assertThat(batch.getDeferred()).isTrue();
    assertThat(batch.getSkipReason()).contains("credit cap");
    assertThat(server.requestBodies).isEmpty();
  }

  @Test
  void aCategoryWhoseDayHasNotComeSaysWhenItWill() {
    CollectedBatch batch = secondOfTheList().collect(null, null, 1000);

    assertThat(batch.getNotDue()).isTrue();
    assertThat(batch.getDeferred()).isFalse();
    assertThat(batch.getSkipReason()).isEqualTo("Not due yet: next pass on 2026-10-23");
    assertThat(server.requestBodies).isEmpty();
  }

  @Test
  void aFirstPassThatDidNotFinishIsTriedAgainOnTheNextRunWhateverTheDay() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    // Its first try, on its own day, failed or met the daily cap: the Scout kept the pass open.
    ScoutCursor open = new ScoutCursor(null, null, "2026-10-16T03:00:00Z");

    CollectedBatch batch = secondOfTheList().collect(open, 1000);

    assertThat(batch.getNotDue()).isFalse();
    assertThat(server.requestBodies).hasSize(1);
  }

  /** The second category of the list starts on day 1 of the week; the fixtures' day is day 0. */
  private TavilyDiscoveryCollector secondOfTheList() {
    return new TavilyDiscoveryCollector(
        new SourceSettings(true, server.baseUrl(), "t", Duration.ofSeconds(2), Duration.ZERO),
        ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new TavilyDiscoveryCollector.Search("advanced", 10),
        SELF_HOSTED,
        List.of(LOCAL_AI, SELF_HOSTED),
        new ScoutTavilyCredits(counter, keys, 40, meters));
  }

  @Test
  void thePassFailsWhenHalfOrMoreOfItsSearchesFail() {
    server.route("/search", new FixtureServer.Reply(500, "{}", Map.of(), 0));
    DiscoveryCategory twoQueries =
        category("voice", ResourceType.PRODUCT, List.of("tts", "music"), List.of());
    assertThatThrownBy(() -> collector(twoQueries).collect(null, null, 1000))
        .isInstanceOf(RuntimeException.class);
    assertThat(server.requestBodies).hasSize(2);
  }

  @Test
  void isOneSourcePerCategoryAndDisabledWithoutATokenOrWhenTheCategoryIsOff() {
    TavilyDiscoveryCollector coding = collector(SELF_HOSTED);
    assertThat(coding.getDescriptor().getId()).isEqualTo("discovery-self-hosted");
    assertThat(coding.getDescriptor().getShortName()).isEqualTo("Short");
    assertThat(coding.getDescriptor().getFamily()).isEqualTo("run-it-yourself");
    assertThat(coding.getDescriptor().getEnabled()).isTrue();
    assertThat(coding.getDescriptor().getResourceTypes())
        .containsExactlyInAnyOrder(ResourceType.PRODUCT, ResourceType.ARTICLE);
    assertThat(collector("", 40, SELF_HOSTED).getDescriptor().getEnabled()).isFalse();
    DiscoveryCategory off =
        options(
            "self-hosted", ResourceType.PRODUCT, List.of("q"), List.of(), 0.4, false, false, false);
    assertThat(collector(off).getDescriptor().getEnabled()).isFalse();
    assertThat(collector(off).getDescriptor().getResourceTypes())
        .containsExactly(ResourceType.PRODUCT);
  }

  @Test
  void aListPageStaysAnArticleWhenItsTitleNamesAnEvent() {
    // An EVENT without event dates escapes both retention rules (news after 180 days, events
    // after they end), so a list page must keep the ARTICLE type it is indexed with.
    server.route(
        "/search",
        FixtureServer.Reply.ok(
            """
            {"results":[
              {"url":"https://www.example-lists.com/blog/best-ai-tools-for-event-planning",
               "title":"10 Best AI Tools for Event Planning",
               "content":"We compare self-hosted AI assistants for planning a conference.","score":0.9}
            ]}\
            """));
    CollectedBatch batch = collector(SELF_HOSTED).collect(null, null, 1000);

    assertThat(batch.getHits())
        .extracting(SourceHit::getType)
        .containsExactly(ResourceType.ARTICLE);
  }

  @Test
  void aProductInMoreCategoriesThanACandidateCanHaveKeepsItsOwnFirstThenTheConfiguredOrder() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    // Every category has the word "self-hosted": every result fits all 15.
    List<DiscoveryCategory> others =
        java.util.stream.IntStream.rangeClosed(1, Candidate.MAX_TAGS + 2)
            .mapToObj(i ->
                category(
                    String.format("category-%02d", i),
                    ResourceType.PRODUCT,
                    List.of("q"),
                    List.of("self-hosted")))
            .toList();
    List<DiscoveryCategory> all = new java.util.ArrayList<>();
    all.add(SELF_HOSTED);
    all.addAll(others);

    CollectedBatch batch =
        collector("tvly-test", 40, all.toArray(DiscoveryCategory[]::new)).collect(null, null, 1000);

    assertThat(batch.getCandidates().getFirst().getTags())
        .hasSize(Candidate.MAX_TAGS)
        .contains("self-hosted", "category-01", "category-11")
        .doesNotContain("category-12", "category-14");
    // A list page is not a candidate: it keeps every category it fits.
    assertThat(batch.getHits().getFirst().getTags()).hasSize(Candidate.MAX_TAGS + 3);
  }

  @Test
  void aCategoryFitsByItsWordsAtTheStartOfAWordWithHyphensAsSpaces() {
    DiscoveryCategory.Keywords words =
        DiscoveryCategory.Keywords.of(List.of("Fine tuning platform", " lora ", "text-to-speech"));
    assertThat(words.words()).containsExactly("fine tuning platform", "lora", "text-to-speech");
    assertThat(words.matchedIn("A Fine-Tuning platform")).containsExactly("fine tuning platform");
    assertThat(words.matchedIn("Train a LoRA adapter")).containsExactly("lora");
    assertThat(words.matchedIn("Explore the flora")).isEmpty();
    assertThat(words.matchedIn("Realistic Text to Speech")).containsExactly("text-to-speech");
  }

  @Test
  void aCategoryNeedsATagIdAShortNameAProductTypeAndAQuery() {
    assertThatThrownBy(() -> category("Self hosted", ResourceType.PRODUCT, List.of("q"), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> category("x".repeat(31), ResourceType.PRODUCT, List.of("q"), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> category("news", ResourceType.NEWS, List.of("q"), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> category("empty", ResourceType.PRODUCT, List.of(" "), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static tools.jackson.databind.JsonNode tree(String json) {
    return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
  }

  /** The day's counters in memory. */
  static final class MapCounter implements QuotaCounter {
    final Map<Counter, Long> counts = new HashMap<>();

    @Override
    public Result take(Counter own, int ownLimit, Counter global, long globalLimit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void add(Counter counter, long amount) {
      counts.merge(counter, amount, Long::sum);
    }

    @Override
    public void giveBack(Counter counter) {}

    @Override
    public long used(Counter counter) {
      return counts.getOrDefault(counter, 0L);
    }

    @Override
    public void delete(Collection<String> keys) {}
  }
}
