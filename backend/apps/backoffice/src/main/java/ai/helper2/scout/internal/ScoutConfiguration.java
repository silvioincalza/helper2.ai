package ai.helper2.scout.internal;

import ai.helper2.catalog.AwesomeListText;
import ai.helper2.catalog.CatalogWriter.Forgotten;
import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ForgetWithdrawnRecords;
import ai.helper2.catalog.usecase.IndexAwesomeLists;
import ai.helper2.catalog.usecase.IndexResources;
import ai.helper2.catalog.usecase.LinkKnownRelationshipTargets;
import ai.helper2.catalog.usecase.ProposeKnownTargets;
import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.platform.ScheduledJob;
import ai.helper2.platform.cache.SharedCaches;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettings;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import ai.helper2.scout.CandidateReview;
import ai.helper2.scout.CatalogIndex;
import ai.helper2.scout.Collector;
import ai.helper2.scout.DiscoveryHostStore;
import ai.helper2.scout.DomainRoots;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentProposal;
import ai.helper2.scout.EnrichmentStore;
import ai.helper2.scout.EvidenceValidator;
import ai.helper2.scout.ExtraEvidence;
import ai.helper2.scout.GapBudget;
import ai.helper2.scout.KnownTargetProposals;
import ai.helper2.scout.KnownTargets;
import ai.helper2.scout.ListIndex;
import ai.helper2.scout.ListText;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.scout.OfficialPages;
import ai.helper2.scout.PageLedger;
import ai.helper2.scout.PageReader;
import ai.helper2.scout.PageTextCache;
import ai.helper2.scout.Proposal;
import ai.helper2.scout.RunCalendar;
import ai.helper2.scout.RunLock;
import ai.helper2.scout.ScheduledRun;
import ai.helper2.scout.ScheduledRunStore;
import ai.helper2.scout.ScoutStore;
import ai.helper2.scout.UnitOfWork;
import ai.helper2.scout.Withdrawal;
import ai.helper2.scout.WithdrawnRecords;
import ai.helper2.scout.admission.AdmissionPolicy;
import ai.helper2.scout.admission.AdmissionRules;
import ai.helper2.scout.admission.IndexFloor;
import ai.helper2.scout.config.ScoutAdmissionProperties;
import ai.helper2.scout.config.ScoutProperties;
import ai.helper2.scout.usecase.CatalogLookup;
import ai.helper2.scout.usecase.EditCandidate;
import ai.helper2.scout.usecase.EnrichResource;
import ai.helper2.scout.usecase.IndexAcceptedCandidate;
import ai.helper2.scout.usecase.ListScoutRuns;
import ai.helper2.scout.usecase.ListedResource;
import ai.helper2.scout.usecase.ManageDiscoveryHosts;
import ai.helper2.scout.usecase.RecheckEnrichments;
import ai.helper2.scout.usecase.RunScout;
import ai.helper2.scout.usecase.StartScoutRun;
import ai.helper2.scout.usecase.StoredAnswers;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.VitalityPolicy;
import ai.helper2.search.WebSearchQuota;
import ai.helper2.sources.config.CollectorDescriptors;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class ScoutConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ScoutConfiguration.class);

  @Bean
  EnrichmentModel enrichmentModel(
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      Clock clock,
      CallCounter budgetCounter,
      ModelCallMetrics metrics,
      MeterRegistry meters,
      Resilience resilience,
      RuntimeSettings runtime,
      ScoutProperties scout) {
    int maxTokens = scout.enrichMaxTokens();
    int dailyCalls = scout.dailyEnrichmentCalls();
    IntSupplier limit = runtime.declare(RuntimeSetting.SCOUT_DAILY_ENRICHMENT_CALLS, dailyCalls);
    ChatModel model = chatModel.getIfAvailable();
    if (!llm.usable(model) || !llm.hasReasoningModel()) {
      log.info("No LLM configured: enrichment disabled, deterministic source metadata only");
      return new UnavailableModel();
    }
    DailyBudget budget = new DailyBudget(limit, clock, "enrichment", budgetCounter);
    Gauge.builder("helper2.scout.enrichment.budget.remaining", budget, DailyBudget::remaining)
        .description("Scout enrichment model calls left under today's cap (UTC day)")
        .register(meters);
    log.info(
        "LLM enrichment enabled: provider={}, model={}, maxTokens={}, dailyCalls={}",
        llm.provider(),
        llm.reasoningModel(),
        maxTokens,
        dailyCalls);
    return new SpringAiEnrichmentModel(
        model, llm.provider(), llm.reasoningModel(), maxTokens, budget, metrics, resilience);
  }

  @Bean
  SettingsGroup scoutSettings(
      ScoutProperties scout, EnrichmentModel enrichmentModel, List<Collector> collectors) {
    return scoutSettingsGroup(
        scout,
        enrichmentModel.getAvailable() ? enrichmentModel.getModel() : null,
        enrichmentModel.getPromptVersion(),
        collectors.stream()
            .map(Collector::getDescriptor)
            .filter(SourceDescriptor::getEnabled)
            .map(SourceDescriptor::getId)
            .toList());
  }

  static SettingsGroup scoutSettingsGroup(
      ScoutProperties scout,
      @Nullable String model,
      String promptVersion,
      List<String> enabledCollectors) {
    return new SettingsGroup(
        "scout",
        "Scout",
        SettingsGroup.SCOUT,
        List.of(
            Setting.flag("scout.scheduleOn", "Daily run on", scout.enabled(), "H2_SCOUT_ENABLED"),
            Setting.schedule(
                "scout.cron", "Daily run at (cron, UTC)", scout.cron(), "H2_SCOUT_CRON"),
            Setting.names(
                "scout.collectorsOn", "Collectors on", enabledCollectors, "H2_SOURCE_<ID>_ENABLED"),
            Setting.fixedCount(
                "scout.maxItemsPerRun", "Items a run collects", scout.maxItemsPerRun(), "items"),
            Setting.count(
                "scout.maxEnrichmentsPerRun",
                "Enrichments a run",
                scout.maxEnrichmentsPerRun(),
                "calls a run",
                "H2_SCOUT_MAX_ENRICHMENTS"),
            Setting.count(
                "scout.dailyEnrichmentCalls",
                "Enrichment calls a day",
                scout.dailyEnrichmentCalls(),
                "calls a day",
                "H2_SCOUT_DAILY_ENRICHMENT_CALLS"),
            Setting.count(
                "scout.gapDailyCalls",
                "Gap rounds a day",
                scout.gapDailyCalls(),
                "calls a day",
                "H2_SCOUT_GAP_DAILY_CALLS"),
            Setting.count(
                "scout.evidenceDailyExtractCredits",
                "Page reads a day, product pages",
                scout.evidence().dailyPageReadCredits(),
                "credits a day",
                "H2_SCOUT_PAGE_READER_DAILY_CREDITS"),
            Setting.count(
                "scout.enrichMaxTokens",
                "Output cap of one enrichment call",
                scout.enrichMaxTokens(),
                "tokens",
                "H2_NEBIUS_ENRICH_MAX_TOKENS"),
            Setting.model("scout.enrichmentModel", "Enrichment model", model, null),
            Setting.version("scout.enrichmentPrompt", "Enrichment prompt version", promptVersion)));
  }

  @Bean
  CatalogIndex catalogIndex(IndexResources indexResources) {
    return indexResources::index;
  }

  @Bean
  WithdrawnRecords withdrawnRecords(ForgetWithdrawnRecords forget) {
    return (sourceId, externalIds) -> {
      Forgotten f = forget.forget(sourceId, externalIds);
      return new Withdrawal(f.records(), f.resources(), f.keptResources());
    };
  }

  @Bean
  KnownTargets knownTargets(LinkKnownRelationshipTargets link) {
    return link::link;
  }

  static final List<String> OWNER_SOURCES =
      List.of(
          "github",
          CollectorDescriptors.GITHUB_AWESOME,
          CollectorDescriptors.GITHUB_TOPIC_LEADERS,
          CollectorDescriptors.GITHUB_SDKS,
          "hf-models",
          "hf-spaces",
          CollectorDescriptors.HF_PROVIDER_ORGS,
          CollectorDescriptors.HF_TRENDING);

  @Bean
  KnownTargetProposals knownTargetProposals(
      KnownTargetCandidates candidates, ScoutProperties scout) {
    ProposeKnownTargets propose =
        new ProposeKnownTargets(
            candidates,
            OWNER_SOURCES,
            scout.knownTargets().ownerMinResources(),
            scout.knownTargets().perDay());
    return () -> {
      KnownTargetCandidates.Proposed proposed = propose.propose();
      return new Proposal(proposed.opened(), proposed.heldBack());
    };
  }

  @Bean
  ListIndex listIndex(IndexAwesomeLists indexAwesomeLists) {
    return lists ->
        indexAwesomeLists.replace(lists.stream().map(ScoutConfiguration::catalogText).toList());
  }

  private static AwesomeListText catalogText(ListText list) {
    return new AwesomeListText(
        list.getUrl(),
        list.getHeadings(),
        list.getEntries().stream()
            .map(e -> new AwesomeListText.Entry(e.getUrl(), e.getText(), e.getHeadingPath()))
            .toList());
  }

  @Bean
  ClusterLock scoutRunLock(ClusterLocks locks) {
    return locks.keptAlive("scout-run", RUN_LOCK_LEASE);
  }

  static final Duration RUN_LOCK_LEASE = Duration.ofMinutes(2);

  @Bean
  AdmissionRules admissionRules(ScoutAdmissionProperties admission) {
    return new AdmissionRules(
        Map.of(
            CollectorDescriptors.GITHUB_TOPIC_LEADERS,
            new IndexFloor(
                admission.githubTopicLeaders().indexMinStars(),
                admission.githubTopicLeaders().indexPushedWithin()),
            CollectorDescriptors.HF_TRENDING,
            new IndexFloor(0, null, admission.hfTrending().indexMinLikes()),
            CollectorDescriptors.PYPI,
            new IndexFloor(0, null, 0, true)));
  }

  @Bean
  AdmissionPolicy admissionPolicy(VitalityPolicy vitality, AdmissionRules rules) {
    return new AdmissionPolicy(vitality, rules);
  }

  @Bean
  RunScout scoutRunner(
      AdmissionPolicy admission,
      List<Collector> collectors,
      ScoutStore store,
      CatalogIndex index,
      EnrichmentModel model,
      EnrichmentStore enrichmentStore,
      EnrichmentBacklog backlog,
      Clock clock,
      ClusterLock scoutRunLock,
      CandidateReview review,
      ListIndex listIndex,
      WithdrawnRecords withdrawnRecords,
      KnownTargets knownTargets,
      KnownTargetProposals knownTargetProposals,
      MeterRegistry meters,
      ObservationRegistry observations,
      ObjectProvider<ExtraEvidence> extraEvidence,
      ObjectProvider<PageReader> pageReader,
      ObjectProvider<DomainRoots> domainRoots,
      ObjectProvider<WebSearchQuota> webSearchQuota,
      ObjectProvider<SharedCaches> sharedCaches,
      ObjectProvider<StringRedisTemplate> redis,
      ObjectMapper json,
      Resilience resilience,
      CallCounter budgetCounter,
      RuntimeSettings runtime,
      ScoutProperties scout) {
    int maxItemsPerRun = scout.maxItemsPerRun();
    IntSupplier maxEnrichmentsPerRun =
        runtime.declare(RuntimeSetting.SCOUT_MAX_ENRICHMENTS_PER_RUN, scout.maxEnrichmentsPerRun());
    IntSupplier gapDailyCalls =
        runtime.declare(RuntimeSetting.SCOUT_GAP_DAILY_CALLS, scout.gapDailyCalls());
    OfficialPages pages =
        officialPages(
            pageReader.getIfAvailable(),
            domainRoots.getIfAvailable(),
            webSearchQuota.getIfAvailable(),
            sharedCaches.getIfAvailable(),
            pageTextCache(redis.getIfAvailable(), json, resilience, scout.evidence()),
            scout.evidence(),
            clock,
            budgetCounter,
            meters);
    return new RunScout(
        collectors,
        store,
        index,
        new EnrichResource(
            model,
            enrichmentStore,
            clock,
            new EvidenceValidator(),
            meters,
            extraEvidence.getIfAvailable(),
            gapBudget(gapDailyCalls, clock, budgetCounter, meters),
            pages),
        clock,
        new ClusterRunLock(scoutRunLock),
        maxItemsPerRun,
        maxEnrichmentsPerRun,
        backlog,
        meters,
        RUN_THREADS,
        observations,
        review,
        listIndex,
        withdrawnRecords,
        knownTargets,
        admission,
        knownTargetProposals);
  }

  static @Nullable OfficialPages officialPages(
      @Nullable PageReader reader,
      @Nullable DomainRoots roots,
      @Nullable WebSearchQuota quota,
      @Nullable SharedCaches caches,
      PageTextCache textCache,
      ScoutProperties.Evidence evidence,
      Clock clock,
      CallCounter counter,
      MeterRegistry meters) {
    if (reader == null
        || !reader.getAvailable()
        || roots == null
        || evidence.dailyPageReadCredits() <= 0) {
      log.info("Evidence ladder: product pages off (no reader, no key or no credits)");
      return null;
    }
    DailyBudget credits =
        new DailyBudget(evidence.dailyPageReadCredits(), clock, "scout-evidence-extract", counter);
    Gauge.builder("helper2.scout.evidence.credits.remaining", credits, DailyBudget::remaining)
        .description("Page reads left for product pages under today's cap (UTC day)")
        .register(meters);
    PageLedger ledger =
        caches == null
            ? PageLedger.NONE
            : new SharedCachePageLedger(
                caches.named(SharedCachePageLedger.CACHE), evidence.pageReadTtl());
    log.info(
        "Evidence ladder: product pages on, {} Extract credits a day",
        evidence.dailyPageReadCredits());
    return new OfficialPages(
        reader,
        new PageReaderCreditBudget(credits, quota, meters),
        roots,
        clock,
        ledger,
        textCache);
  }

  static PageTextCache pageTextCache(
      @Nullable StringRedisTemplate redis,
      ObjectMapper json,
      Resilience resilience,
      ScoutProperties.Evidence evidence) {
    return redis == null
        ? PageTextCache.NONE
        : new RedisPageTextCache(redis, json, resilience, evidence.pageTextTtl());
  }

  static GapBudget gapBudget(
      IntSupplier dailyCalls, Clock clock, CallCounter counter, MeterRegistry meters) {
    DailyBudget budget = new DailyBudget(dailyCalls, clock, "enrichment-gap", counter);
    Gauge.builder("helper2.scout.gap.budget.remaining", budget, DailyBudget::remaining)
        .description("Scout gap-round model calls left under today's cap (UTC day)")
        .register(meters);
    return new GapBudget() {
      @Override
      public boolean tryAcquire() {
        return budget.tryAcquire();
      }

      @Override
      public boolean hasRoom() {
        return budget.remaining() > 0;
      }
    };
  }

  @Bean
  IndexAcceptedCandidate indexAcceptedCandidate(
      List<Collector> collectors,
      CatalogIndex index,
      ReviewQueue queue,
      ResourceCandidateJson details,
      Clock clock) {
    CatalogLookup lookup =
        url ->
            queue
                .listedAs(url.getValue())
                .map(l -> new ListedResource(l.resourceId(), l.title()))
                .orElse(null);
    return new IndexAcceptedCandidate(collectors, index, lookup, details, clock);
  }

  @Bean
  EditCandidate editCandidate(
      ReviewQueueCandidateItems items, AuditTrail audit, UnitOfWork scoutUnitOfWork) {
    return new EditCandidate(items, audit, scoutUnitOfWork);
  }

  @Bean
  StartScoutRun startScoutRun(RunScout scoutRunner, AuditTrail audit) {
    return new StartScoutRun(scoutRunner, audit);
  }

  @Bean
  UnitOfWork scoutUnitOfWork(PlatformTransactionManager transactions) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    return new UnitOfWork() {
      @Override
      public <T> T run(Supplier<T> work) {
        return template.execute(status -> work.get());
      }
    };
  }

  @Bean
  ListScoutRuns listScoutRuns(ScoutStore store) {
    return new ListScoutRuns(store);
  }

  @Bean
  ManageDiscoveryHosts manageDiscoveryHosts(
      DiscoveryHostStore hosts, AuditTrail audit, UnitOfWork scoutUnitOfWork, Clock clock) {
    return new ManageDiscoveryHosts(hosts, audit, scoutUnitOfWork, clock);
  }

  @Bean
  RecheckEnrichments recheckEnrichments(
      StoredAnswers answers,
      EnrichmentStore enrichmentStore,
      EnrichmentModel model,
      AuditTrail audit,
      MeterRegistry meters,
      ClusterLock scoutRunLock) {
    return new RecheckEnrichments(
        answers,
        enrichmentStore,
        model.getPromptVersion(),
        model.getAvailable(),
        audit,
        meters,
        new ClusterRunLock(scoutRunLock));
  }

  private static final ContextSnapshotFactory RUN_CONTEXT =
      ContextSnapshotFactory.builder()
          .captureKeyPredicate(ObservationThreadLocalAccessor.KEY::equals)
          .build();

  static final Executor RUN_THREADS =
      task -> Thread.ofVirtual().name("scout-run").start(RUN_CONTEXT.captureAll().wrap(task));

  @Bean
  ApplicationListener<ApplicationReadyEvent> startScout(
      RunScout runner, RecheckEnrichments recheck, ScoutProperties scout) {
    return event -> {
      closeInterruptedRuns(runner);
      if (scout.enabled()) {
        startFirstRecheck(recheck);
      }
    };
  }

  private static void closeInterruptedRuns(RunScout runner) {
    try {
      if (!runner.closeInterruptedRuns()) {
        log.info(
            "interrupted scout runs not closed at startup: another replica holds the scout-run lock");
      }
    } catch (RuntimeException e) {
      log.warn(
          "closing interrupted scout runs at startup failed: {}", e.getClass().getSimpleName());
    }
  }

  private static void startFirstRecheck(RecheckEnrichments recheck) {
    Thread.ofVirtual()
        .name("enrichment-recheck")
        .start(() -> {
          try {
            recheck.runOnce();
          } catch (RuntimeException e) {
            log.warn("enrichment re-check failed: {}", e.getClass().getSimpleName());
          }
        });
  }

  static final class ClusterRunLock implements RunLock {
    private final ClusterLock lock;

    ClusterRunLock(ClusterLock lock) {
      this.lock = lock;
    }

    @Override
    public <T> @Nullable T runExclusively(Supplier<T> task) {
      return lock.tryCall(task);
    }
  }

  static final class UnavailableModel implements EnrichmentModel {
    @Override
    public String getProvider() {
      return "none";
    }

    @Override
    public String getModel() {
      return "none";
    }

    @Override
    public String getPromptVersion() {
      return SpringAiEnrichmentModel.PROMPT_VERSION;
    }

    @Override
    public boolean getAvailable() {
      return false;
    }

    @Override
    public EnrichmentProposal propose(EnrichmentInput input) {
      throw new LlmUnavailable("no LLM provider configured");
    }
  }

  static final class CronRunCalendar implements RunCalendar {
    private final CronExpression cron;

    CronRunCalendar(String cron) {
      this.cron = CronExpression.parse(cron);
    }

    @Override
    public Instant nextRunAfter(Instant lastEnd) {
      ZonedDateTime next = cron.next(lastEnd.atZone(ZoneOffset.UTC));
      if (next == null) {
        throw new IllegalStateException("helper2.scout.cron never fires again");
      }
      return next.toInstant();
    }
  }

  @Component
  @ConditionalOnProperty(name = "helper2.scout.enabled", havingValue = "true")
  static class ScoutSchedule implements SchedulingConfigurer {
    private final RunScout runner;
    private final ScheduledRunStore schedule;
    private final RunCalendar calendar;
    private final Duration initialDelay;
    private final Duration every;

    ScoutSchedule(
        RunScout runner, ScheduledRunStore schedule, EnrichmentModel model, ScoutProperties scout) {
      this.runner = runner;
      this.schedule = schedule;
      String cron = scout.cron();
      this.calendar = new CronRunCalendar(cron);
      this.initialDelay = scout.initialDelay();
      this.every = scout.tick();
      String when =
          "cron '"
              + cron
              + "' UTC; checked "
              + initialDelay
              + " after startup, then every "
              + every
              + "; a run missed while no replica was up starts at the next check";
      if (model.getAvailable()) {
        log.info(
            "Scout scheduled ({}), enrichment with {}/{}",
            when,
            model.getProvider(),
            model.getModel());
      } else {
        log.warn(
            "Scout scheduled ({}) WITHOUT an LLM: new resources are not categorized. "
                + "Start with the llm-nebius profile (and H2_NEBIUS_API_KEY) to enable enrichment.",
            when);
      }
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
      registrar.addFixedDelayTask(
          new FixedDelayTask(new ScheduledJob("scout-tick", this::tick), every, initialDelay));
    }

    void tick() {
      if (runner.tryRunScheduled(calendar, schedule) == ScheduledRun.BUSY) {
        log.info(
            "scheduled scout run deferred: another run is in progress; the next tick tries again");
      }
    }
  }
}
