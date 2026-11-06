package ai.helper2.sources.config;

import ai.helper2.platform.cache.SharedCaches;
import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.OutboundThrottle;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.sources.aideadlines.AiDeadlinesCollector;
import ai.helper2.sources.arxiv.ArxivSource;
import ai.helper2.sources.classifier.RepoClassifier;
import ai.helper2.sources.classifier.SpringAiRepoClassifier;
import ai.helper2.sources.evidence.PublicSuffixDomains;
import ai.helper2.sources.evidence.ReadmeEvidence;
import ai.helper2.sources.evidence.TavilyExtract;
import ai.helper2.sources.github.GitHubAwesomeCollector;
import ai.helper2.sources.github.GitHubSource;
import ai.helper2.sources.huggingface.HuggingFaceModelsSource;
import ai.helper2.sources.huggingface.HuggingFaceProviderOrgsCollector;
import ai.helper2.sources.huggingface.HuggingFaceSpacesSource;
import ai.helper2.sources.huggingface.HuggingFaceTrendingCollector;
import ai.helper2.sources.mcp.McpRegistryCollector;
import ai.helper2.sources.npm.NpmSource;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.CourseCollector;
import ai.helper2.sources.tavily.LinkedInCollector;
import ai.helper2.sources.tavily.ScoutTavilyCredits;
import ai.helper2.sources.tavily.TavilyCollector;
import ai.helper2.sources.tavily.TavilySource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  SourcesConfiguration.SourcesProperties.class,
  CollectorProperties.HfTrending.class,
  CollectorProperties.TavilyScout.class,
  CollectorProperties.Courses.class,
  CollectorProperties.ScoutCredits.class,
  CollectorProperties.Discovery.class,
  CollectorProperties.LinkedIn.class,
  CollectorProperties.GitHubAwesome.class,
  CollectorProperties.HfProviderOrgs.class,
  CollectorProperties.AiDeadlines.class,
  CollectorProperties.McpRegistry.class
})
@Profile("!demo")
@Import(DiscoveryCollectorRegistrar.class)
class SourcesConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SourcesConfiguration.class);

  /**
   * Every source and collector under {@code helper2.sources.<id>}. The defaults are in
   * application.yaml; only the deadline is derived here (twice the timeout when empty).
   */
  @Validated
  @ConfigurationProperties("helper2")
  record SourcesProperties(@NotNull Map<String, @Valid SourceSettings> sources) {
    SourceSettings get(String key) {
      SourceSettings s = sources.get(key);
      if (s == null) {
        throw new IllegalStateException("missing helper2.sources." + key);
      }
      if (s.cacheTtl() == null) {
        throw new IllegalStateException("missing helper2.sources." + key + ".cache-ttl");
      }
      return new SourceSettings(
          s.enabled(),
          s.baseUrl(),
          s.token(),
          s.timeout(),
          s.cacheTtl(),
          s.deadline() == null ? s.timeout().multipliedBy(2) : s.deadline());
    }
  }

  /**
   * A Scout collector's client: its calls may take up to the source's deadline in all
   * (H2_SOURCE_<ID>_DEADLINE, default twice the timeout). Search sources keep their timeout as the
   * deadline.
   */
  static RestClient.Builder collectorClient(SourceSettings s, ObservationRegistry observations) {
    return ObservedHttp.restClientBuilder(s.timeout(), s.deadline(), observations);
  }

  @Bean
  GitHubSource gitHubSource(SourcesProperties p, Clock clock, ObservationRegistry observations) {
    SourceSettings s = p.get("github");
    return new GitHubSource(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
  }

  @Bean
  HuggingFaceModelsSource huggingFaceModelsSource(
      SourcesProperties p, Clock clock, ObservationRegistry observations) {
    SourceSettings s = p.get("huggingface");
    return new HuggingFaceModelsSource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
  }

  @Bean
  HuggingFaceSpacesSource huggingFaceSpacesSource(
      SourcesProperties p, Clock clock, ObservationRegistry observations) {
    SourceSettings s = p.get("huggingface");
    return new HuggingFaceSpacesSource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
  }

  @Bean
  NpmSource npmSource(SourcesProperties p, Clock clock, ObservationRegistry observations) {
    SourceSettings s = p.get("npm");
    return new NpmSource(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
  }

  @Bean
  ArxivSource arxivSource(
      SourcesProperties p,
      Clock clock,
      ObservationRegistry observations,
      OutboundThrottle throttle) {
    SourceSettings s = p.get("arxiv");
    return new ArxivSource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock, throttle);
  }

  @Bean
  TavilySource tavilySource(SourcesProperties p, Clock clock, ObservationRegistry observations) {
    SourceSettings s = p.get("tavily");
    return new TavilySource(s, ObservedHttp.restClientBuilder(s.timeout(), observations), clock);
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
      CollectorProperties.TavilyScout c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations) {
    SourceSettings s = p.get("tavily-scout");
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
   * list (ADR 0053). Its credits count on the Scout's daily cap, as discovery's.
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
   * The fast model classifies awesome-list entries when an LLM is configured; otherwise keyword
   * rules do. Its daily call cap is counted in Redis, so a restart does not reset the day.
   */
  @Bean
  GitHubAwesomeCollector gitHubAwesomeCollector(
      SourcesProperties p,
      CollectorProperties.GitHubAwesome c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      CallCounter budgetCounter,
      ModelCallMetrics metrics,
      MeterRegistry meters) {
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
        classifier);
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
   * available) without H2_TAVILY_API_KEY.
   */
  @Bean
  TavilyExtract tavilyExtract(
      SourcesProperties p, Clock clock, Resilience resilience, ObservationRegistry observations) {
    SourceSettings s = p.get("tavily-extract");
    return new TavilyExtract(s, collectorClient(s, observations), resilience, clock);
  }

  /** Registrable domains (Public Suffix List) for the pages the evidence ladder may read. */
  @Bean
  PublicSuffixDomains publicSuffixDomains() {
    return new PublicSuffixDomains();
  }

  /**
   * README and model-card text for the Scout's enrichment (ADR 0043, ADR 0056), with the GitHub and
   * Hub settings and tokens. The ETag and hash of each GitHub README read go in the shared cache,
   * so an unchanged README answers 304; without the cache every check reads in full.
   */
  @Bean
  ReadmeEvidence readmeEvidence(
      SourcesProperties p,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      ObjectProvider<SharedCaches> caches) {
    SourceSettings github = p.get("github");
    SourceSettings hub = p.get("huggingface");
    SharedCaches shared = caches.getIfAvailable();
    return ReadmeEvidence.of(
        github,
        hub,
        ObservedHttp.restClientBuilder(github.timeout(), observations),
        ObservedHttp.restClientBuilder(hub.timeout(), observations),
        resilience,
        clock,
        shared == null ? null : shared.named(ReadmeEvidence.REVISION_CACHE));
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
