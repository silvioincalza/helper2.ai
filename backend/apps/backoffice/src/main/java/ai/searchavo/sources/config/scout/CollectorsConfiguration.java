package ai.searchavo.sources.config.scout;

import ai.searchavo.catalog.DirectoryRatingIndex;
import ai.searchavo.catalog.Resources;
import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.platform.config.HttpClientProperties;
import ai.searchavo.platform.http.ObservedHttp;
import ai.searchavo.platform.llm.CallCounter;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.LlmSettings;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.quota.QuotaCounter;
import ai.searchavo.platform.quota.QuotaKeys;
import ai.searchavo.platform.resilience.OutboundThrottle;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.platform.settings.RuntimeSetting;
import ai.searchavo.platform.settings.RuntimeSettings;
import ai.searchavo.platform.settings.Setting;
import ai.searchavo.platform.settings.SettingsGroup;
import ai.searchavo.scout.DiscoveredLists;
import ai.searchavo.scout.DiscoveryHostStore;
import ai.searchavo.scout.DomainRoots;
import ai.searchavo.scout.GoneListSeeds;
import ai.searchavo.scout.GoneWebPageRule;
import ai.searchavo.scout.HostsNeverRead;
import ai.searchavo.scout.ListSeedStore;
import ai.searchavo.scout.PageStatusReader;
import ai.searchavo.scout.admission.AdmissionPolicy;
import ai.searchavo.scout.admission.AdmissionRules;
import ai.searchavo.scout.admission.IndexFloor;
import ai.searchavo.scout.usecase.AddDiscoveredList;
import ai.searchavo.search.DescribedSource;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.VitalityPolicy;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.CategoryMatcher;
import ai.searchavo.shared.CollectorIds;
import ai.searchavo.sources.aideadlines.AiDeadlinesCollector;
import ai.searchavo.sources.arxiv.scout.ArxivPaperReader;
import ai.searchavo.sources.classifier.ItemPageJudge;
import ai.searchavo.sources.classifier.RepoClassifier;
import ai.searchavo.sources.classifier.SpringAiItemPageJudge;
import ai.searchavo.sources.classifier.SpringAiRepoClassifier;
import ai.searchavo.sources.config.CollectorDescriptors;
import ai.searchavo.sources.config.CollectorProperties;
import ai.searchavo.sources.config.SourcesProperties;
import ai.searchavo.sources.confstech.ConfsTechCollector;
import ai.searchavo.sources.depsdev.DepsDevClient;
import ai.searchavo.sources.depsdev.PackageFinder;
import ai.searchavo.sources.events.EventReader;
import ai.searchavo.sources.events.SpringAiEventReader;
import ai.searchavo.sources.events.UndatedEvents;
import ai.searchavo.sources.evidence.LiveWebPageReader;
import ai.searchavo.sources.evidence.PublicSuffixDomains;
import ai.searchavo.sources.evidence.ReadmeEvidence;
import ai.searchavo.sources.github.scout.DirectoryRatingReader;
import ai.searchavo.sources.github.scout.GitHubAwesomeCollector;
import ai.searchavo.sources.github.scout.GitHubRepositoryStates;
import ai.searchavo.sources.github.scout.GitHubSdkCollector;
import ai.searchavo.sources.github.scout.GitHubTopicLeadersCollector;
import ai.searchavo.sources.github.scout.GoneWebPages;
import ai.searchavo.sources.github.scout.ItemPageReads;
import ai.searchavo.sources.github.scout.ListLinkFollower;
import ai.searchavo.sources.github.scout.SdkSearchPlan;
import ai.searchavo.sources.github.scout.WebPageLists;
import ai.searchavo.sources.huggingface.scout.HuggingFaceModelReader;
import ai.searchavo.sources.huggingface.scout.HuggingFaceProviderOrgsCollector;
import ai.searchavo.sources.huggingface.scout.HuggingFaceTrendingCollector;
import ai.searchavo.sources.liveweb.scout.CourseCollector;
import ai.searchavo.sources.liveweb.scout.CoursePages;
import ai.searchavo.sources.liveweb.scout.LinkedInCollector;
import ai.searchavo.sources.liveweb.scout.LiveWebEventsCollector;
import ai.searchavo.sources.liveweb.scout.LiveWebNewsCollector;
import ai.searchavo.sources.liveweb.scout.ScoutLiveWebCredits;
import ai.searchavo.sources.mcp.McpRegistryCollector;
import ai.searchavo.sources.npm.scout.NpmPackageReader;
import ai.searchavo.sources.pagestatus.LivePageStatusReader;
import ai.searchavo.sources.pypi.PypiCollector;
import ai.searchavo.sources.pypi.PypiCollectors;
import ai.searchavo.sources.research.PublicAddresses;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.RunInterval;
import ai.searchavo.sources.support.SourceSettings;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
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
  CollectorProperties.ConfsTech.class,
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
      ScoutLiveWebCredits credits,
      ShadowLibraries shadowLibraries) {
    SourceSettings s = p.get("live-web-scout");
    return new LiveWebNewsCollector(
        s,
        collectorClient(s, observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()),
        c.queries(),
        credits,
        shadowLibraries);
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
      RuntimeSettings runtime,
      MeterRegistry meters) {
    IntSupplier dailyCredits =
        runtime.declare(RuntimeSetting.SCOUT_SEARCH_DAILY_CREDITS, c.dailyCredits());
    return new ScoutLiveWebCredits(counter, keys, dailyCredits::getAsInt, meters);
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
      MeterRegistry meters,
      Resources catalog,
      ShadowLibraries shadowLibraries) {
    SourceSettings s = p.get(LiveWebEventsCollector.ID);
    ChatModel model = chatModel.getIfAvailable();
    EventReader reader = null;
    if (llm.usable(model) && llm.has(c.readModel())) {
      DailyBudget budget =
          new DailyBudget(c.readerDailyCalls(), clock, "event-reader", budgetCounter);
      Gauge.builder("searchavo.scout.event-reader.budget.remaining", budget, DailyBudget::remaining)
          .description("Event page reader model calls left under today's cap (UTC day)")
          .register(meters);
      reader =
          new SpringAiEventReader(
              model, llm.model(c.readModel()), c.readMaxTokens(), budget, metrics, resilience);
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
        reader,
        (since, after, limit) ->
            catalog
                .undatedEvents(
                    since,
                    after == null ? null : after.discoveredAt(),
                    after == null ? null : after.id(),
                    limit)
                .stream()
                .map(e ->
                    new UndatedEvents.Page(
                        e.url(), e.title(), new UndatedEvents.Position(e.discoveredAt(), e.id())))
                .toList(),
        shadowLibraries);
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
                "SA_SCOUT_DAILY_CREDITS"),
            Setting.count(
                "scoutSources.classifierDailyCalls",
                "Awesome-list classifier calls a day",
                awesome.classifierDailyCalls(),
                "calls a day",
                "SA_SCOUT_AWESOME_CLASSIFIER_DAILY_CALLS")));
  }

  @Bean
  GitHubAwesomeCollector gitHubAwesomeCollector(
      SourcesProperties p,
      CollectorProperties.GitHubAwesome c,
      CollectorProperties.Discovery discovery,
      CollectorProperties.Courses courses,
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
      DiscoveryHostStore hosts,
      ListSeedStore addedLists,
      ShadowLibraries shadowLibraries,
      VitalityPolicy vitality,
      OutboundThrottle throttle,
      WebPageLists webPageLists,
      DirectoryRatingReader directoryRatings) {
    SourceSettings s = p.get("github-awesome");
    ChatModel model = chatModel.getIfAvailable();
    RepoClassifier classifier;
    if (!llm.usable(model) || llm.fast().isEmpty()) {
      classifier = new RepoClassifier.Heuristic();
    } else {
      DailyBudget budget =
          new DailyBudget(c.classifierDailyCalls(), clock, "classifier", budgetCounter);
      Gauge.builder("searchavo.scout.classifier.budget.remaining", budget, DailyBudget::remaining)
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
        hosts,
        addedLists,
        shadowLibraries,
        new GitHubAwesomeCollector.LinkRules(
            new CoursePages(courses.platforms()),
            modelReader(p.get("hf-trending"), clock, resilience, observations, http),
            listedModelAdmission(vitality, c.links().modelIndexMinLikes()),
            paperReader(p.get("arxiv"), s, clock, resilience, observations, http, throttle),
            packageReader(p.get("npm"), s, clock, resilience, observations, http)),
        webPageLists,
        directoryRatings);
  }

  @Bean
  DescribedSource curatedWebListsSource(SourcesProperties p) {
    SourceDescriptor descriptor =
        CollectorDescriptors.curatedWebLists(p.get(CollectorIds.GITHUB_AWESOME));
    return new DescribedSource() {
      @Override
      public SourceDescriptor getDescriptor() {
        return descriptor;
      }
    };
  }

  @Bean
  HostsNeverRead listPageHostsNeverRead(CollectorProperties.GitHubAwesome c) {
    return new HostsNeverRead(c.webPages().neverReadReasons());
  }

  @Bean
  WebPageLists webPageLists(
      CollectorProperties.GitHubAwesome c,
      ListSeedStore addedLists,
      LiveWebPageReader reader,
      ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      Clock clock,
      ListLinkFollower follower,
      GoneWebPages gone) {
    return new WebPageLists(
        addedLists,
        reader,
        credits,
        neverRead,
        c.webPages().readEvery(),
        c.webPages().retryAfter(),
        c.webPages().discoveredReadsPerDay(),
        clock,
        follower,
        gone);
  }

  @Bean
  GoneWebPages goneWebPages(
      CollectorProperties.GitHubAwesome c,
      PageStatusReader statuses,
      ListSeedStore addedLists,
      GoneWebPageRule catalog,
      GoneListSeeds records,
      MeterRegistry meters) {
    return new GoneWebPages(
        statuses, addedLists, catalog, records, c.webPages().goneChecks(), meters);
  }

  @Bean
  ItemPageReads itemPageReads() {
    return new ItemPageReads();
  }

  @Bean
  PageStatusReader pageStatusReader(
      CollectorProperties.GitHubAwesome c,
      ShadowLibraries shadowLibraries,
      HostsNeverRead neverRead,
      Resilience resilience,
      QuotaCounter counter,
      QuotaKeys keys,
      Clock clock) {
    CollectorProperties.GitHubAwesome.WebPages.Follow follow = c.webPages().follow();
    return new LivePageStatusReader(
        new PublicAddresses(shadowLibraries),
        neverRead,
        resilience,
        counter,
        keys,
        new LivePageStatusReader.Limits(
            follow.statusRequestsPerDay(), follow.statusRequestsPerHostPerDay()),
        clock);
  }

  @Bean
  ItemPageJudge itemPageJudge(
      CollectorProperties.GitHubAwesome c,
      Clock clock,
      Resilience resilience,
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      CallCounter budgetCounter,
      ModelCallMetrics metrics,
      MeterRegistry meters) {
    ChatModel model = chatModel.getIfAvailable();
    CollectorProperties.GitHubAwesome.WebPages.Follow follow = c.webPages().follow();
    if (!llm.usable(model) || llm.fast().isEmpty() || follow.checkCallsPerDay() == 0) {
      return ItemPageJudge.NOT_ASKED;
    }
    DailyBudget budget =
        new DailyBudget(follow.checkCallsPerDay(), clock, "follow-check", budgetCounter);
    Gauge.builder("searchavo.scout.follow.check.budget.remaining", budget, DailyBudget::remaining)
        .description("Calls of the model that checks a followed page left under today's cap")
        .register(meters);
    return new SpringAiItemPageJudge(
        model, llm.fast(), follow.checkMaxTokens(), budget, metrics, resilience);
  }

  @Bean
  ListLinkFollower listLinkFollower(
      CollectorProperties.GitHubAwesome c,
      CollectorProperties.Discovery discovery,
      ShadowLibraries shadowLibraries,
      DomainRoots domains,
      LiveWebPageReader reader,
      PageStatusReader statuses,
      ItemPageJudge judge,
      ScoutLiveWebCredits credits,
      QuotaCounter counter,
      QuotaKeys keys,
      HostsNeverRead neverRead,
      ItemPageReads itemPages,
      MeterRegistry meters) {
    return new ListLinkFollower(
        deep(c, discovery).excludeDomains(),
        shadowLibraries,
        domains,
        reader,
        statuses,
        judge,
        credits,
        counter,
        keys,
        neverRead,
        itemPages,
        c.webPages().follow().stepsPerDay(),
        meters);
  }

  @Bean
  DirectoryRatingReader directoryRatingReader(
      CollectorProperties.GitHubAwesome c,
      DirectoryRatingIndex ratings,
      LiveWebPageReader reader,
      ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      MeterRegistry meters,
      Clock clock,
      ItemPageReads itemPages) {
    return new DirectoryRatingReader(
        ratings,
        reader,
        credits,
        neverRead,
        c.webPages().readEvery(),
        c.webPages().itemPageReadsPerDay(),
        meters,
        clock,
        itemPages);
  }

  @Bean
  DiscoveredLists discoveredLists(
      CollectorProperties.GitHubAwesome c,
      ListSeedStore addedLists,
      HostsNeverRead neverRead,
      Clock clock) {
    return new AddDiscoveredList(addedLists, neverRead, c.webPages().maxDiscovered(), clock);
  }

  private static final String AWESOME_POLICY = "collector-" + CollectorIds.GITHUB_AWESOME;

  private static @Nullable ArxivPaperReader paperReader(
      SourceSettings arxiv,
      SourceSettings collector,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http,
      OutboundThrottle throttle) {
    if (!arxiv.enabled()) {
      return null;
    }
    return new ArxivPaperReader(
        collectorClient(collector, observations, http).baseUrl(arxiv.baseUrl()).build(),
        resilience,
        AWESOME_POLICY,
        clock,
        throttle,
        ArxivPaperReader.sleeping());
  }

  private static @Nullable NpmPackageReader packageReader(
      SourceSettings npm,
      SourceSettings collector,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    if (!npm.enabled()) {
      return null;
    }
    return new NpmPackageReader(
        collectorClient(collector, observations, http).baseUrl(npm.baseUrl()).build(),
        resilience,
        AWESOME_POLICY,
        clock);
  }

  static AdmissionPolicy listedModelAdmission(VitalityPolicy vitality, int minLikes) {
    return new AdmissionPolicy(
        vitality,
        new AdmissionRules(Map.of(CollectorIds.GITHUB_AWESOME, new IndexFloor(0, null, minLikes))));
  }

  private static @Nullable HuggingFaceModelReader modelReader(
      SourceSettings hub,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    if (!hub.enabled()) {
      return null;
    }
    return new HuggingFaceModelReader(
        HttpClients.client(collectorClient(hub, observations, http), hub),
        resilience,
        "collector-" + CollectorIds.GITHUB_AWESOME,
        clock);
  }

  @Bean
  @Nullable GitHubRepositoryStates gitHubRepositoryStates(
      SourcesProperties p,
      Resilience resilience,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("github");
    if (!s.enabled() || !s.hasToken()) {
      return null;
    }
    resilience.require(GitHubRepositoryStates.POLICY);
    return new GitHubRepositoryStates(
        HttpClients.gitHubClient(CollectorsConfiguration.collectorClient(s, observations, http), s),
        resilience,
        clock);
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
        c.links().maxModelsPerPass(),
        c.links().maxPapersPerPass(),
        c.links().maxPackagesPerPass(),
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
        ObservedHttp.restClientBuilderWithoutRedirects(
            s.timeout(), s.deadline(), observations, http),
        clock,
        resilience,
        new RunInterval(c.interval()));
  }

  @Bean
  ConfsTechCollector confsTechCollector(
      SourcesProperties p,
      CollectorProperties.ConfsTech c,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get(CollectorIds.CONFS_TECH);
    return new ConfsTechCollector(
        s,
        collectorClient(s, observations, http),
        ObservedHttp.restClientBuilderWithoutRedirects(
            s.timeout(), s.deadline(), observations, http),
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
            searchScopeOfWhatIsIndexed(admission.of(CollectorIds.GITHUB_TOPIC_LEADERS), c)),
        GitHubSdkCollector.Pause.sleeping());
  }

  private static GitHubTopicLeadersCollector.Indexing searchScopeOfWhatIsIndexed(
      IndexFloor floor, CollectorProperties.GitHubTopicLeaders c) {
    return new GitHubTopicLeadersCollector.Indexing(
        Math.toIntExact(floor.getMinStars()),
        Objects.requireNonNull(
            floor.getPushedWithin(),
            "searchavo.scout.admission.github-topic-leaders.index-pushed-within"),
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
    SourceSettings s = p.get(CollectorIds.GITHUB_SDKS);
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
                ReviewQueue.Kind.RESOURCE_CANDIDATE, CollectorIds.GITHUB_SDKS);
      }
    };
  }
}
