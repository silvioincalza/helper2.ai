package ai.helper2.sources.tavily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TavilyDiscoveryCollectorTest {

  static final String SELF_HOSTED =
      "PRODUCT|self-hosted|open-source self-hosted AI app|self-hosted;self hosted";
  static final String LOCAL_AI =
      "PRODUCT|local-ai|desktop app to run LLMs locally|offline;run locally";
  static final String AGENTS = "AGENT|agent-platforms|platform to build AI agents|ai agents";

  static final String RESULTS =
      """
      {"results":[
        {"url":"https://www.example-lists.com/blog/12-best-self-hosted-llm-apps","title":"12 Best Self-Hosted LLM Apps in 2026",
         "content":"We compare Open WebUI, LibreChat and AnythingLLM for running AI models offline at home.","score":0.91},
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

  @AfterEach
  void stop() {
    server.close();
  }

  TavilyDiscoveryCollector collector(String token, List<String> categories) {
    return new TavilyDiscoveryCollector(
        new SourceSettings(true, server.baseUrl(), token, Duration.ofSeconds(2), Duration.ZERO),
        ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofDays(7)),
        new TavilyDiscoveryCollector.Search("advanced", 10, 0.4, List.of("youtube.com")),
        categories);
  }

  @Test
  void searchesOncePerCategoryOnTheGeneralWebWithoutAnAnswer() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
    collector("tvly-test", List.of(SELF_HOSTED, AGENTS)).collect(null, null, 1000);

    assertThat(server.requestBodies).hasSize(2);
    assertThat(server.requestBodies.get(0))
        .contains("\"query\":\"open-source self-hosted AI app\"")
        .contains("\"topic\":\"general\"")
        .contains("\"search_depth\":\"advanced\"")
        .contains("\"max_results\":10")
        .contains("\"include_answer\":false")
        .contains("\"exclude_domains\":[\"youtube.com\"]")
        .contains("\"time_range\":\"month\"")
        .doesNotContain("start_date");
    assertThat(server.requestBodies.get(1)).contains("platform to build AI agents");
  }

  @Test
  void aLaterPassAsksOnlyForPagesSinceThePreviousOne() {
    server.route("/search", FixtureServer.Reply.ok("{\"results\":[]}"));
    collector("tvly-test", List.of(SELF_HOSTED)).collect("2026-10-01T03:07:00Z", null, 1000);

    assertThat(server.requestBodies.getFirst())
        .contains("\"start_date\":\"2026-10-01\"")
        .doesNotContain("time_range");
  }

  @Test
  void indexesListPagesAsArticlesAndProposesProductSites() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch =
        collector("tvly-test", List.of(SELF_HOSTED, LOCAL_AI)).collect(null, null, 1000);

    assertThat(batch.getHits())
        .extracting(SourceHit::getCanonicalUrl)
        .extracting(CanonicalUrl::getValue)
        .containsExactly("https://example-lists.com/blog/12-best-self-hosted-llm-apps");
    assertThat(batch.getHits().getFirst().getType()).isEqualTo(ResourceType.ARTICLE);
    assertThat(batch.getHits().getFirst().getSourceId()).isEqualTo("tavily-discovery");

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
    CollectedBatch batch = collector("tvly-test", List.of(SELF_HOSTED)).collect(null, null, 1000);

    assertThat(batch.getHits())
        .extracting(SourceHit::getType)
        .containsExactly(ResourceType.ARTICLE);
  }

  @Test
  void aResultGetsEveryCategoryItBelongsTo() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch =
        collector("tvly-test", List.of(SELF_HOSTED, LOCAL_AI)).collect(null, null, 1000);

    Map<String, Candidate> byUrl =
        batch.getCandidates().stream()
            .collect(Collectors.toMap(c -> c.getUrl().getValue(), c -> c));
    assertThat(byUrl.get("https://openwebui.com").getTags())
        .containsExactlyInAnyOrder("self-hosted", "local-ai");
    assertThat(byUrl.get("https://openwebui.com").getPlatform()).isEqualTo("local-ai, self-hosted");
    assertThat(batch.getHits().getFirst().getTags())
        .containsExactlyInAnyOrder("self-hosted", "local-ai");
  }

  @Test
  void aProductFoundByAnAgentSearchIsAnAgentWithEveryTag() {
    server.route("/search", FixtureServer.Reply.ok(RESULTS));
    CollectedBatch batch =
        collector("tvly-test", List.of(SELF_HOSTED, AGENTS)).collect(null, null, 1000);

    assertThat(batch.getCandidates()).hasSize(3);
    assertThat(batch.getCandidates()).allMatch(c -> c.getType() == ResourceType.AGENT);
    assertThat(batch.getCandidates().getFirst().getTags())
        .contains("self-hosted", "agent-platforms");
    assertThat(batch.getHits()).hasSize(1);
  }

  @Test
  void thePassFailsOnlyWhenEverySearchFails() {
    server.route("/search", new FixtureServer.Reply(500, "{}", Map.of(), 0));
    assertThatThrownBy(
            () -> collector("tvly-test", List.of(SELF_HOSTED, AGENTS)).collect(null, null, 1000))
        .isInstanceOf(RuntimeException.class);
    assertThat(server.requestBodies).hasSize(2);
  }

  @Test
  void isDisabledWithoutATokenOrWithoutCategories() {
    assertThat(collector("", List.of(SELF_HOSTED)).getDescriptor().getEnabled()).isFalse();
    assertThat(collector("tvly-test", List.of()).getDescriptor().getEnabled()).isFalse();
    assertThat(collector("tvly-test", List.of(SELF_HOSTED)).getDescriptor().getEnabled()).isTrue();
  }

  @Test
  void readsACategoryOnlyWithAProductTypeATagAndAQuery() {
    assertThat(TavilyDiscoveryCollector.Category.parse(" agent | agents | agent platform "))
        .isEqualTo(
            new TavilyDiscoveryCollector.Category(
                ResourceType.AGENT, "agents", "agent platform", List.of()));
    assertThat(TavilyDiscoveryCollector.Category.parse("PRODUCT|fine-tuning|q| Fine-Tun ; lora ;"))
        .isEqualTo(
            new TavilyDiscoveryCollector.Category(
                ResourceType.PRODUCT, "fine-tuning", "q", List.of("fine-tun", "lora")));
    assertThat(TavilyDiscoveryCollector.Category.parse("NEWS|news|ai news")).isNull();
    assertThat(TavilyDiscoveryCollector.Category.parse("COURSE|courses|ai course")).isNull();
    assertThat(TavilyDiscoveryCollector.Category.parse("WIDGET|x|query")).isNull();
    assertThat(TavilyDiscoveryCollector.Category.parse("PRODUCT|query only")).isNull();
    assertThat(TavilyDiscoveryCollector.Category.parse("PRODUCT||query")).isNull();
    assertThat(TavilyDiscoveryCollector.Category.parse("PRODUCT|Self hosted|query")).isNull();
  }

  @Test
  void aCategoryFitsByItsWordsAtTheStartOfAWord() {
    TavilyDiscoveryCollector.Category fineTuning =
        TavilyDiscoveryCollector.Category.parse("PRODUCT|fine-tuning|q|fine-tun;lora");
    assertThat(fineTuning.describes("Fine-tuning made easy")).isTrue();
    assertThat(fineTuning.describes("Train a LoRA adapter")).isTrue();
    assertThat(fineTuning.describes("Explore the flora")).isFalse();
  }

  @Test
  void hyphensAndSpacesAreTheSameInACategorysWords() {
    TavilyDiscoveryCollector.Category voice =
        TavilyDiscoveryCollector.Category.parse("PRODUCT|voice|q|text-to-speech;no code");
    assertThat(voice.describes("Realistic text to speech")).isTrue();
    assertThat(voice.describes("Text-to-Speech API")).isTrue();
    assertThat(voice.describes("A no-code builder")).isTrue();
  }

  @Test
  void aProductSiteIsAHomePageOrOneLevelBelowItButNotWritingAboutOne() {
    assertThat(site("https://cursor.com/")).isEqualTo("https://cursor.com");
    assertThat(site("https://cursor.com/features")).isEqualTo("https://cursor.com");
    // A big vendor has one product per page: the page is the product, not the company.
    assertThat(site("https://aws.amazon.com/bedrock")).isEqualTo("https://aws.amazon.com/bedrock");
    // A language segment does not count.
    assertThat(site("https://deepl.com/en")).isEqualTo("https://deepl.com/en");
    assertThat(site("https://deepl.com/de/pricing")).isEqualTo("https://deepl.com");
    assertThat(site("https://deepl.com/en/blog/new-model")).isNull();
    // AI directories link products; they are not one.
    assertThat(site("https://theresanaiforthat.com/")).isNull();
    assertThat(site("https://cursor.com/blog")).isNull();
    assertThat(site("https://cursor.com/docs/agent")).isNull();
    assertThat(site("https://cursor.com/why-we-rebuilt-our-editor")).isNull();
    assertThat(site("https://docs.cursor.com/")).isNull();
    assertThat(site("https://someone.substack.com/")).isNull();
    assertThat(site("https://github.com/ollama/ollama"))
        .isEqualTo("https://github.com/ollama/ollama");
    assertThat(site("https://github.com/ollama")).isNull();
    assertThat(site("https://github.com/topics/llm")).isNull();
  }

  @Test
  void aListTitleCompares() {
    CanonicalUrl page = CanonicalUrl.of("https://example.com/some-page");
    assertThat(TavilyDiscoveryCollector.isListPage("12 Best AI Writing Tools", page)).isTrue();
    assertThat(TavilyDiscoveryCollector.isListPage("Best AI coding tools in 2026", page)).isTrue();
    assertThat(TavilyDiscoveryCollector.isListPage("Cursor vs Windsurf", page)).isTrue();
    assertThat(TavilyDiscoveryCollector.isListPage("Top ChatGPT alternatives", page)).isTrue();
    assertThat(TavilyDiscoveryCollector.isListPage("Acme – the best way to run LLMs", page))
        .isFalse();
    assertThat(TavilyDiscoveryCollector.isListPage("An open-source alternative to ChatGPT", page))
        .isFalse();
  }

  @Test
  void theTitleOfAPageBelowTheHomePageIsTheSiteName() {
    CanonicalUrl site = CanonicalUrl.of("https://anythingllm.com/");
    CanonicalUrl pricing = CanonicalUrl.of("https://anythingllm.com/pricing");
    assertThat(TavilyDiscoveryCollector.siteTitle("Pricing | AnythingLLM", pricing, site))
        .isEqualTo("AnythingLLM");
    assertThat(TavilyDiscoveryCollector.siteTitle("AnythingLLM - Pricing", pricing, site))
        .isEqualTo("AnythingLLM");
    assertThat(TavilyDiscoveryCollector.siteTitle("Plans and prices", pricing, site))
        .isEqualTo("anythingllm.com");
  }

  private static String site(String url) {
    CanonicalUrl found = TavilyDiscoveryCollector.productSite(CanonicalUrl.of(url));
    return found == null ? null : found.getValue();
  }
}
