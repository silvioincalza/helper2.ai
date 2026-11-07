package ai.helper2.sources.config;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import ai.helper2.scout.DiscoveryHostStore;
import ai.helper2.shared.CategoryMatcher;
import ai.helper2.sources.aideadlines.AiDeadlinesCollector;
import ai.helper2.sources.classifier.RepoClassifier;
import ai.helper2.sources.classifier.SpringAiRepoClassifier;
import ai.helper2.sources.events.EventReader;
import ai.helper2.sources.events.SpringAiEventReader;
import ai.helper2.sources.evidence.PublicSuffixDomains;
import ai.helper2.sources.evidence.ReadmeEvidence;
import ai.helper2.sources.evidence.TavilyExtract;
import ai.helper2.sources.github.GitHubAwesomeCollector;
import ai.helper2.sources.huggingface.HuggingFaceProviderOrgsCollector;
import ai.helper2.sources.huggingface.HuggingFaceTrendingCollector;
import ai.helper2.sources.mcp.McpRegistryCollector;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.CourseCollector;
import ai.helper2.sources.tavily.LinkedInCollector;
import ai.helper2.sources.tavily.LiveWebEventsCollector;
import ai.helper2.sources.tavily.ScoutTavilyCredits;
import ai.helper2.sources.tavily.TavilyCollector;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * The Scout's collectors and the evidence readers (ADR 0018, 0043, 0056): the back office runs
 * them. The live search sources are the sources library's ({@code LiveSourcesConfiguration}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  SourcesProperties.class,
  CollectorProperties.HfTrending.class,
  CollectorProperties.LiveWebScout.class,
  CollectorProperties.Courses.class,
  CollectorProperties.ScoutCredits.class,
  CollectorProperties.Discovery.class,
  CollectorProperties.LinkedIn.class,
  CollectorProperties.LiveWebEvents.class,
  CollectorProperties.GitHubAwesome.class,
  CollectorProperties.HfProviderOrgs.class,
  CollectorProperties.AiDeadlines.class,
  CollectorProperties.McpRegistry.class
})
@Profile("!demo")
@Import(DiscoveryCollectorRegistrar.class)
class CollectorsConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CollectorsConfiguration.class);

  /**
   * A Scout collector's client: its calls may take up to the source's deadline in all
   * (H2_SOURCE_<ID>_DEADLINE, default twice the timeout). Search sources keep their timeout as the
   * deadline.
   */
  static RestClient.Builder collectorClient(SourceSettings s, ObservationRegistry observations) {
    return ObservedHttp.restClientBuilder(s.timeout(), s.deadline(), observations);
  }

  @Bean
  HuggingFaceTrendingCollector huggingFaceTrendingCollector(
      SourcesProperties p,
      CollectorProperties.HfTrending c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations) {
    SourceSettings s = p.get("hf-trending");
    return new HuggingFaceTrendingCollector(
        s,
        collectorClient(s, observations),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.minLikes());
  }

  @Bean
  TavilyCollector tavilyCollector(
      SourcesProperties p,
      CollectorProperties.LiveWebScout c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations) {
    SourceSettings s = p.get("live-web-scout");
    return new TavilyCollector(
        s,
        collectorClient(s, observations),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.queries());
  }

  /**
   * Weekly Tavily searches on learning platforms; what it finds goes to the admin review list (ADR
   * 0032 §8).
   */
  @Bean
  CourseCollector courseCollector(
      SourcesProperties p,
      CollectorProperties.Courses c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations) {
    SourceSettings s = p.get("course-scout");
    return new CourseCollector(
        s,
        collectorClient(s, observations),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.query(),
        c.platforms());
  }

  /**
   * The Tavily credits the Scout's searches spend (discovery and LinkedIn), counted with the live
   * search's and capped per UTC day (ADR 0051). The discovery collectors themselves are registered
   * per category by {@link DiscoveryCollectorRegistrar}.
   */
  @Bean
  ScoutTavilyCredits scoutTavilyCredits(
      CollectorProperties.ScoutCredits c,
      QuotaCounter counter,
      QuotaKeys keys,
      MeterRegistry meters) {
    return new ScoutTavilyCredits(counter, keys, c.dailyCredits(), meters);
  }

  /**
   * Weekly Tavily searches restricted to linkedin.com; every page it keeps goes to the admin review
   * list (ADR 0061). Its credits count on the Scout's daily cap, as discovery's.
   */
  @Bean
  LinkedInCollector linkedInCollector(
      SourcesProperties p,
      CollectorProperties.LinkedIn c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      ScoutTavilyCredits credits) {
    SourceSettings s = p.get("linkedin-scout");
    return new LinkedInCollector(
        s,
        collectorClient(s, observations),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.queries(),
        credits);
  }

  /**
   * Weekly Tavily searches for upcoming AI events; the reasoning model reads each page found and
   * code checks its days against the page (ADR 0067). Without a model the collector is off. Its
   * credits count on the Scout's daily cap; its model calls on their own daily cap, counted in
   * Redis.
   */
  @Bean
  LiveWebEventsCollector liveWebEventsCollector(
      SourcesProperties p,
      CollectorProperties.LiveWebEvents c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      ScoutTavilyCredits credits,
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      CallCounter budgetCounter,
      ModelCallMetrics metrics,
      MeterRegistry meters) {
    SourceSettings s = p.get(LiveWebEventsCollector.ID);
    ChatModel model = chatModel.getIfAvailable();
    EventReader reader = null;
    if (llm.usable(model)) {
      DailyBudget budget =
          new DailyBudget(c.readerDailyCalls(), clock, "event-reader", budgetCounter);
      Gauge.builder("helper2.scout.event-reader.budget.remaining", budget, DailyBudget::remaining)
          .description("Event page reader model calls left under today's cap (UTC day)")
          .register(meters);
      reader =
          new SpringAiEventReader(
              model, llm.reasoningModel(), c.readMaxTokens(), budget, metrics, resilience);
    }
    return new LiveWebEventsCollector(
        s,
        collectorClient(s, observations),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.queries(),
        c.maxPages(),
        credits,
        reader);
  }

  /** The Scout sources' daily caps for the back office's Settings page (ADR 0045). */
  @Bean
  SettingsGroup scoutSourceSettings(
      CollectorProperties.ScoutCredits credits, CollectorProperties.GitHubAwesome awesome) {
    return new SettingsGroup(
        "scoutSources",
        "Scout sources",
        SettingsGroup.SCOUT_SOURCES,
        List.of(
            Setting.count(
                "scoutSources.dailyCredits",
                "Live web credits a day, Scout searches",
                credits.dailyCredits(),
                "credits a day",
                "H2_SCOUT_DAILY_CREDITS"),
            Setting.count(
                "scoutSources.classifierDailyCalls",
                "Awesome-list classifier calls a day",
                awesome.classifierDailyCalls(),
                "calls a day",
                "H2_SCOUT_AWESOME_CLASSIFIER_DAILY_CALLS")));
  }

  /**
   * The fast model classifies awesome-list entries when an LLM is configured; otherwise keyword
   * rules do. Its daily call cap is counted in Redis, so a restart does not reset the day.
   */
  @Bean
  GitHubAwesomeCollector gitHubAwesomeCollector(
      SourcesProperties p,
      CollectorProperties.GitHubAwesome c,
      CollectorProperties.Discovery discovery,
      CategoryMatcher categories,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      CallCounter budgetCounter,
      ModelCallMetrics metrics,
      MeterRegistry meters,
      DiscoveryHostStore hosts) {
    SourceSettings s = p.get("github-awesome");
    ChatModel model = chatModel.getIfAvailable();
    RepoClassifier classifier;
    if (!llm.usable(model) || llm.fast().isEmpty()) {
      classifier = new RepoClassifier.Heuristic();
    } else {
      DailyBudget budget =
          new DailyBudget(c.classifierDailyCalls(), clock, "classifier", budgetCounter);
      Gauge.builder("helper2.scout.classifier.budget.remaining", budget, DailyBudget::remaining)
          .description("Awesome-list classifier model calls left under today's cap (UTC day)")
          .register(meters);
      classifier =
          new SpringAiRepoClassifier(
              model, llm.fast(), c.classifyMaxTokens(), budget, metrics, resilience);
    }
    log.info(
        "GitHub awesome lists: enabled={}, classifier={}",
        s.enabled() && s.hasToken(),
        classifier.name());
    return new GitHubAwesomeCollector(
        s,
        collectorClient(s, observations),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.topicQueries(),
        new GitHubAwesomeCollector.Limits(
            c.maxLists(),
            c.minListStars(),
            c.maxEntriesPerPass(),
            c.maxEntriesPerRun(),
            c.minRepoStars(),
            c.classifyBatch()),
        deep(c, discovery),
        classifier,
        categories,
        meters,
        hosts);
  }

  /**
   * Deep search (ADR 0058): a product link is skipped on discovery's excluded domains too, so a
   * list and a search keep the same sites out.
   */
  private static GitHubAwesomeCollector.Deep deep(
      CollectorProperties.GitHubAwesome c, CollectorProperties.Discovery discovery) {
    List<String> excluded = new ArrayList<>(discovery.excludeDomains());
    excluded.addAll(c.links().excludeDomains());
    return new GitHubAwesomeCollector.Deep(
        c.nested().maxDepth(),
        c.nested().maxListsPerRun(),
        c.maxRunTime(),
        c.maxReadmeBytesPerRun().toBytes(),
        c.links().maxPerPass(),
        c.links().perDay(),
        excluded);
  }

  @Bean
  HuggingFaceProviderOrgsCollector huggingFaceProviderOrgsCollector(
      SourcesProperties p,
      CollectorProperties.HfProviderOrgs c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations) {
    SourceSettings s = p.get("hf-provider-orgs");
    return new HuggingFaceProviderOrgsCollector(
        s,
        collectorClient(s, observations),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.orgs(),
        c.perOrg());
  }

  @Bean
  AiDeadlinesCollector aiDeadlinesCollector(
      SourcesProperties p,
      CollectorProperties.AiDeadlines c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations) {
    SourceSettings s = p.get("ai-deadlines");
    return new AiDeadlinesCollector(
        s, collectorClient(s, observations), clock, resilience, new RunInterval(c.interval()));
  }

  /**
   * A product's own pages for the Scout's evidence ladder (ADR 0056): Tavily Extract. Off (not
   * available) without H2_LIVE_WEB_API_KEY.
   */
  @Bean
  TavilyExtract tavilyExtract(
      SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations) {
    SourceSettings s = p.get("page-reader");
    return new TavilyExtract(s, collectorClient(s, observations), resilience, clock);
  }

  /** Registrable domains (Public Suffix List) for the pages the evidence ladder may read. */
  @Bean
  PublicSuffixDomains publicSuffixDomains() {
    return new PublicSuffixDomains();
  }

  /**
   * README and model-card text for the Scout's gap round (ADR 0043), with the GitHub and Hub
   * settings and tokens.
   */
  @Bean
  ReadmeEvidence readmeEvidence(
      SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations) {
    SourceSettings github = p.get("github");
    SourceSettings hub = p.get("huggingface");
    return ReadmeEvidence.of(
        github,
        hub,
        ObservedHttp.restClientBuilder(github.timeout(), observations),
        ObservedHttp.restClientBuilder(hub.timeout(), observations),
        resilience,
        clock);
  }

  @Bean
  McpRegistryCollector mcpRegistryCollector(
      SourcesProperties p,
      CollectorProperties.McpRegistry c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations) {
    SourceSettings s = p.get("mcp-registry");
    return new McpRegistryCollector(
        s, collectorClient(s, observations), clock, resilience, new RunInterval(c.interval()));
  }
}
