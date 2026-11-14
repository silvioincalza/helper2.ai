package ai.helper2.sources.config.scout;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.config.HttpClientProperties;
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
import ai.helper2.scout.admission.AdmissionPolicy;
import ai.helper2.scout.admission.AdmissionRules;
import ai.helper2.scout.admission.IndexFloor;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CategoryMatcher;
import ai.helper2.sources.aideadlines.AiDeadlinesCollector;
import ai.helper2.sources.classifier.RepoClassifier;
import ai.helper2.sources.classifier.SpringAiRepoClassifier;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.config.CollectorProperties;
import ai.helper2.sources.config.SourcesProperties;
import ai.helper2.sources.depsdev.DepsDevClient;
import ai.helper2.sources.depsdev.PackageFinder;
import ai.helper2.sources.events.EventReader;
import ai.helper2.sources.events.SpringAiEventReader;
import ai.helper2.sources.evidence.LiveWebPageReader;
import ai.helper2.sources.evidence.PublicSuffixDomains;
import ai.helper2.sources.evidence.ReadmeEvidence;
import ai.helper2.sources.github.scout.GitHubAwesomeCollector;
import ai.helper2.sources.github.scout.GitHubSdkCollector;
import ai.helper2.sources.github.scout.GitHubTopicLeadersCollector;
import ai.helper2.sources.github.scout.SdkSearchPlan;
import ai.helper2.sources.huggingface.scout.HuggingFaceProviderOrgsCollector;
import ai.helper2.sources.huggingface.scout.HuggingFaceTrendingCollector;
import ai.helper2.sources.liveweb.scout.CourseCollector;
import ai.helper2.sources.liveweb.scout.LinkedInCollector;
import ai.helper2.sources.liveweb.scout.LiveWebEventsCollector;
import ai.helper2.sources.liveweb.scout.LiveWebNewsCollector;
import ai.helper2.sources.liveweb.scout.ScoutLiveWebCredits;
import ai.helper2.sources.mcp.McpRegistryCollector;
import ai.helper2.sources.pypi.PypiCollector;
import ai.helper2.sources.pypi.PypiCollectors;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
  CollectorProperties.GitHubTopicLeaders.class,
  CollectorProperties.GitHubSdks.class,
  CollectorProperties.AiDeadlines.class,
  CollectorProperties.McpRegistry.class,
  CollectorProperties.NewsFeeds.class,
  CollectorProperties.Pypi.class
})
@Profile("!demo")
@Import({DiscoveryCollectorRegistrar.class, NewsFeedCollectorRegistrar.class})
class CollectorsConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CollectorsConfiguration.class);

  static RestClient.Builder collectorClient(
      SourceSettings s, ObservationRegistry observations, HttpClientProperties http) {
    return ObservedHttp.restClientBuilder(s.timeout(), s.deadline(), observations, http);
  }

  @Bean
  HuggingFaceTrendingCollector huggingFaceTrendingCollector(
      SourcesProperties p,
      CollectorProperties.HfTrending c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("hf-trending");
    return new HuggingFaceTrendingCollector(
        s,
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()));
  }

  @Bean
  LiveWebNewsCollector liveWebNewsCollector(
      SourcesProperties p,
      CollectorProperties.LiveWebScout c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http,
      ScoutLiveWebCredits credits) {
    SourceSettings s = p.get("live-web-scout");
    return new LiveWebNewsCollector(
        s,
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.queries(),
        credits);
  }

  @Bean
  CourseCollector courseCollector(
      SourcesProperties p,
      CollectorProperties.Courses c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http,
      ScoutLiveWebCredits credits) {
    SourceSettings s = p.get("course-scout");
    return new CourseCollector(
        s,
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.query(),
        c.platforms(),
        credits);
  }

  @Bean
  ScoutLiveWebCredits scoutLiveWebCredits(
      CollectorProperties.ScoutCredits c,
      QuotaCounter counter,
      QuotaKeys keys,
      MeterRegistry meters) {
    return new ScoutLiveWebCredits(counter, keys, c.dailyCredits(), meters);
  }

  @Bean
  LinkedInCollector linkedInCollector(
      SourcesProperties p,
      CollectorProperties.LinkedIn c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http,
      ScoutLiveWebCredits credits) {
    SourceSettings s = p.get("linkedin-scout");
    return new LinkedInCollector(
        s,
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.queries(),
        credits);
  }

  @Bean
  LiveWebEventsCollector liveWebEventsCollector(
      SourcesProperties p,
      CollectorProperties.LiveWebEvents c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http,
      ScoutLiveWebCredits credits,
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
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.queries(),
        c.maxPages(),
        credits,
        reader);
  }

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

  @Bean
  GitHubAwesomeCollector gitHubAwesomeCollector(
      SourcesProperties p,
      CollectorProperties.GitHubAwesome c,
      CollectorProperties.Discovery discovery,
      CategoryMatcher categories,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http,
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
        collectorClient(s, observations, http),
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
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("hf-provider-orgs");
    return new HuggingFaceProviderOrgsCollector(
        s,
        collectorClient(s, observations, http),
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
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("ai-deadlines");
    return new AiDeadlinesCollector(
        s,
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()));
  }

  @Bean
  LiveWebPageReader liveWebPageReader(
      SourcesProperties p,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("page-reader");
    return new LiveWebPageReader(s, collectorClient(s, observations, http), resilience, clock);
  }

  @Bean
  PublicSuffixDomains publicSuffixDomains() {
    return new PublicSuffixDomains();
  }

  @Bean
  ReadmeEvidence readmeEvidence(
      SourcesProperties p,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings github = p.get("github");
    SourceSettings hub = p.get("huggingface");
    return ReadmeEvidence.of(
        github,
        hub,
        ObservedHttp.restClientBuilder(github.timeout(), observations, http),
        ObservedHttp.restClientBuilder(hub.timeout(), observations, http),
        resilience,
        clock);
  }

  @Bean
  PypiCollector pypiCollector(
      SourcesProperties p,
      CollectorProperties.Pypi c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http,
      AdmissionPolicy admission) {
    return PypiCollectors.create(
        p.get("pypi"),
        c,
        collectorClient(p.get("pypi"), observations, http).build(),
        clock,
        resilience,
        admission);
  }

  @Bean
  McpRegistryCollector mcpRegistryCollector(
      SourcesProperties p,
      CollectorProperties.McpRegistry c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("mcp-registry");
    return new McpRegistryCollector(
        s,
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.itemsPerRun());
  }

  @Bean
  GitHubTopicLeadersCollector gitHubTopicLeadersCollector(
      SourcesProperties p,
      CollectorProperties.GitHubTopicLeaders c,
      AdmissionRules admission,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("github-topic-leaders");
    return new GitHubTopicLeadersCollector(
        s,
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()),
        new GitHubTopicLeadersCollector.Limits(
            c.topics(),
            c.minStars(),
            c.perTopic(),
            c.perDay(),
            searchScopeOfWhatIsIndexed(admission.of(CollectorDescriptors.GITHUB_TOPIC_LEADERS), c)),
        GitHubSdkCollector.Pause.sleeping());
  }

  private static GitHubTopicLeadersCollector.Indexing searchScopeOfWhatIsIndexed(
      IndexFloor floor, CollectorProperties.GitHubTopicLeaders c) {
    return new GitHubTopicLeadersCollector.Indexing(
        Math.toIntExact(floor.getMinStars()),
        Objects.requireNonNull(
            floor.getPushedWithin(),
            "helper2.scout.admission.github-topic-leaders.index-pushed-within"),
        c.indexPerTopic(),
        c.searchPause());
  }

  @Bean
  GitHubSdkCollector gitHubSdkCollector(
      SourcesProperties p,
      CollectorProperties.GitHubSdks c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http,
      ReviewQueue reviewQueue) {
    SourceSettings s = p.get(CollectorDescriptors.GITHUB_SDKS);
    SdkSearchPlan plan =
        SdkSearchPlan.of(
            c.topics(),
            c.languages().stream().map(SdkSearchPlan.LanguageFloor::parse).toList(),
            c.orgs(),
            c.orgMinStars());
    return new GitHubSdkCollector(
        s,
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()),
        new GitHubSdkCollector.Limits(
            plan,
            Set.copyOf(c.orgs()),
            c.perQuery(),
            c.pagesPerQuery(),
            c.queriesPerRun(),
            c.rateLimitReserve(),
            c.searchPause(),
            c.perDay()),
        new GitHubSdkCollector.Ports(
            new PackageFinder(
                depsDevClient(s, c, clock, resilience, observations, http), c.packagesPerRepo()),
            reviewRoom(reviewQueue, c.perDay()),
            GitHubSdkCollector.Pause.sleeping()),
        CollectorDescriptors.gitHubSdks(s, c.languages(), c.orgs()));
  }

  static DepsDevClient depsDevClient(
      SourceSettings s,
      CollectorProperties.GitHubSdks c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    return new DepsDevClient(
        collectorClient(s, observations, http).build(),
        c.depsDevUrl(),
        clock,
        resilience,
        GitHubSdkCollector.PACKAGES_POLICY);
  }

  static GitHubSdkCollector.ReviewRoom reviewRoom(ReviewQueue queue, int perDay) {
    return new GitHubSdkCollector.ReviewRoom() {
      @Override
      public boolean known(CanonicalUrl url) {
        return queue.listedAs(url.getValue()).isPresent()
            || queue.repositoryListed(url.getValue())
            || queue.candidateKnown(ReviewQueue.Kind.RESOURCE_CANDIDATE, url.getValue());
      }

      @Override
      public int roomToday() {
        return perDay
            - queue.candidatesOpenedToday(
                ReviewQueue.Kind.RESOURCE_CANDIDATE, CollectorDescriptors.GITHUB_SDKS);
      }
    };
  }
}
