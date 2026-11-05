package ai.helper2.scout.internal;

import ai.helper2.catalog.AwesomeListText;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.IndexAwesomeLists;
import ai.helper2.catalog.usecase.IndexResources;
import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
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
import ai.helper2.scout.DomainRoots;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentProposal;
import ai.helper2.scout.EnrichmentQueueControl;
import ai.helper2.scout.EnrichmentStore;
import ai.helper2.scout.EvidenceValidator;
import ai.helper2.scout.ExtraEvidence;
import ai.helper2.scout.GapBudget;
import ai.helper2.scout.ListIndex;
import ai.helper2.scout.ListText;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.scout.OfficialPages;
import ai.helper2.scout.PageLedger;
import ai.helper2.scout.PageReader;
import ai.helper2.scout.PageTextCache;
import ai.helper2.scout.RunCalendar;
import ai.helper2.scout.RunLock;
import ai.helper2.scout.ScheduledRun;
import ai.helper2.scout.ScheduledRunStore;
import ai.helper2.scout.ScoutStore;
import ai.helper2.scout.UnitOfWork;
import ai.helper2.scout.config.ScoutProperties;
import ai.helper2.scout.usecase.CatalogLookup;
import ai.helper2.scout.usecase.EnrichResource;
import ai.helper2.scout.usecase.IndexAcceptedCandidate;
import ai.helper2.scout.usecase.PrioritiseEnrichment;
import ai.helper2.scout.usecase.RecheckEnrichments;
import ai.helper2.scout.usecase.RequeueEnrichment;
import ai.helper2.scout.usecase.RunScout;
import ai.helper2.scout.usecase.SkipEnrichment;
import ai.helper2.scout.usecase.StartScoutRun;
import ai.helper2.scout.usecase.StoredAnswers;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.WebSearchQuota;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class ScoutConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ScoutConfiguration.class);

  /**
   * Uses the reasoning model when one is configured (spring.ai.model.chat,
   * helper2.llm.reasoning-model); otherwise reports LLM_UNAVAILABLE. Its own daily cap, apart from
   * the guide's: the Scout runs on a schedule and must not spend the credits visitors need.
   */
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
    // Declared with or without a model, so the Settings page knows the default either way.
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
    // Recorded on every enrichment run, so provenance says which provider actually produced a
    // proposal.
    log.info(
        "LLM enrichment enabled: provider={}, model={}, maxTokens={}, dailyCalls={}",
        llm.provider(),
        llm.reasoningModel(),
        maxTokens,
        dailyCalls);
    return new SpringAiEnrichmentModel(
        model, llm.provider(), llm.reasoningModel(), maxTokens, budget, metrics, resilience);
  }

  /** The Scout's settings for the back office's Settings page (ADR 0045). */
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
                "Tavily Extract credits a day, product pages",
                scout.evidence().dailyExtractCredits(),
                "credits a day",
                "H2_SCOUT_EVIDENCE_DAILY_EXTRACT_CREDITS"),
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

  /** The catalog's search index of awesome lists (ADR 0055). */
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

  /**
   * Held by every Scout run, scheduled or from the admin button, on whichever replica, for as long
   * as the run lives: a lease of {@link #RUN_LOCK_LEASE}, extended every half lease. A pod that
   * dies mid-run frees it within one lease. Not held after a run ends, so the admin button works
   * right after a run.
   */
  @Bean
  ClusterLock scoutRunLock(ClusterLocks locks) {
    return locks.keptAlive("scout-run", RUN_LOCK_LEASE);
  }

  /**
   * Long enough that a pause of the renewal thread (GC, a slow database) does not let the lease
   * lapse.
   */
  static final Duration RUN_LOCK_LEASE = Duration.ofMinutes(2);

  @Bean
  RunScout scoutRunner(
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
        listIndex);
  }

  /**
   * The evidence ladder's page step (ADR 0056), or null when it is off: no reader (demo profile),
   * no Tavily key, or no credits a day. Its credits have their own daily cap and count on the
   * global Tavily cap; the pages read lately are kept by URL and hash in the shared cache.
   */
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
        || evidence.dailyExtractCredits() <= 0) {
      log.info("Evidence ladder: product pages off (no reader, no key or no credits)");
      return null;
    }
    DailyBudget credits =
        new DailyBudget(evidence.dailyExtractCredits(), clock, "scout-evidence-extract", counter);
    Gauge.builder("helper2.scout.evidence.credits.remaining", credits, DailyBudget::remaining)
        .description("Tavily Extract credits left for product pages under today's cap (UTC day)")
        .register(meters);
    PageLedger ledger =
        caches == null
            ? PageLedger.NONE
            : new SharedCachePageLedger(
                caches.named(SharedCachePageLedger.CACHE), evidence.pageReadTtl());
    log.info(
        "Evidence ladder: product pages on, {} Extract credits a day",
        evidence.dailyExtractCredits());
    return new OfficialPages(
        reader, new TavilyCreditBudget(credits, quota, meters), roots, clock, ledger, textCache);
  }

  /**
   * The cleaned text of product pages for a retry within the day (ADR 0056): Redis only, for
   * page-text-ttl (at most a day). Without Redis, nothing is kept and a retry reads again.
   */
  static PageTextCache pageTextCache(
      @Nullable StringRedisTemplate redis,
      ObjectMapper json,
      Resilience resilience,
      ScoutProperties.Evidence evidence) {
    return redis == null
        ? PageTextCache.NONE
        : new RedisPageTextCache(redis, json, resilience, evidence.pageTextTtl());
  }

  /**
   * The gap round's own daily cap (ADR 0043), counted like the others so a restart does not reset
   * the day. Its calls also count on the enrichment cap, so this is a share of it, not more. 0
   * turns the gap round off; the limit is a runtime setting (ADR 0058), read on every call.
   */
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
    CatalogLookup lookup = url -> queue.inCatalog(url.getValue());
    return new IndexAcceptedCandidate(collectors, index, lookup, details, clock);
  }

  @Bean
  StartScoutRun startScoutRun(RunScout scoutRunner, AuditTrail audit) {
    return new StartScoutRun(scoutRunner, audit);
  }

  /** One database transaction for an admin's change and its audit entry. */
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

  /** The admin's actions on the enrichment queue (ADR 0045 §7). */
  @Bean
  RequeueEnrichment requeueEnrichment(
      EnrichmentBacklog backlog,
      EnrichmentQueueControl control,
      AuditTrail audit,
      UnitOfWork scoutUnitOfWork,
      Clock clock) {
    return new RequeueEnrichment(backlog, control, audit, scoutUnitOfWork, clock);
  }

  @Bean
  SkipEnrichment skipEnrichment(
      EnrichmentBacklog backlog,
      EnrichmentQueueControl control,
      AuditTrail audit,
      UnitOfWork scoutUnitOfWork,
      Clock clock) {
    return new SkipEnrichment(backlog, control, audit, scoutUnitOfWork, clock);
  }

  @Bean
  PrioritiseEnrichment prioritiseEnrichment(
      EnrichmentBacklog backlog,
      EnrichmentQueueControl control,
      AuditTrail audit,
      UnitOfWork scoutUnitOfWork,
      Clock clock) {
    return new PrioritiseEnrichment(backlog, control, audit, scoutUnitOfWork, clock);
  }

  /**
   * Re-checks stored enrichment answers by code (ADR 0056): once after the first start with it,
   * then whenever an admin asks. Resources go back to the backlog only when a model is configured.
   */
  @Bean
  RecheckEnrichments recheckEnrichments(
      StoredAnswers answers,
      EnrichmentStore enrichmentStore,
      EnrichmentModel model,
      AuditTrail audit,
      MeterRegistry meters) {
    return new RecheckEnrichments(
        answers, enrichmentStore, model.getPromptVersion(), model.getAvailable(), audit, meters);
  }

  /**
   * The first re-check runs on its own virtual thread after startup, claimed once across the
   * cluster; a failure is logged and an admin can run it again. Only where the Scout is scheduled
   * (prod): that is where the backlog it fills is worked off.
   */
  @Bean
  @ConditionalOnProperty(name = "helper2.scout.enabled", havingValue = "true")
  ApplicationListener<ApplicationReadyEvent> recheckEnrichmentsOnce(RecheckEnrichments recheck) {
    return event ->
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

  /**
   * Only the observation: the run must not inherit the admin's SecurityContext (least privilege).
   */
  private static final ContextSnapshotFactory RUN_CONTEXT =
      ContextSnapshotFactory.builder()
          .captureKeyPredicate(ObservationThreadLocalAccessor.KEY::equals)
          .build();

  /**
   * A run started from the admin page collects on its own virtual thread; the request answers at
   * once. The thread carries the request's observation, so its log lines have the request's trace
   * and span ids (ADR 0026).
   */
  static final Executor RUN_THREADS =
      task -> Thread.ofVirtual().name("scout-run").start(RUN_CONTEXT.captureAll().wrap(task));

  /**
   * At startup, run records still RUNNING belong to a replica that stopped mid-run (a crash, a
   * deploy): closed as FAILED unless a run holds the lock, in which case the next run's start
   * closes them.
   */
  @Bean
  ApplicationListener<ApplicationReadyEvent> closeInterruptedScoutRuns(RunScout runner) {
    return event -> {
      try {
        if (!runner.closeInterruptedRuns()) {
          log.info("interrupted scout runs not checked at startup: a run is in progress");
        }
      } catch (RuntimeException e) {
        log.warn(
            "closing interrupted scout runs at startup failed: {}", e.getClass().getSimpleName());
      }
    };
  }

  /** The RunLock port over a lock shared by every replica. */
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

  /**
   * A fixed time of day (helper2.scout.cron, UTC): the run after one that ended at a given time is
   * the cron's next time after it. A long run or a late tick does not move the next start.
   */
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
  static class ScoutSchedule {
    private final RunScout runner;
    private final ScheduledRunStore schedule;
    private final RunCalendar calendar;

    ScoutSchedule(
        RunScout runner, ScheduledRunStore schedule, EnrichmentModel model, ScoutProperties scout) {
      this.runner = runner;
      this.schedule = schedule;
      String cron = scout.cron();
      this.calendar = new CronRunCalendar(cron);
      Duration initialDelay = scout.initialDelay();
      Duration tick = scout.tick();
      String when =
          "cron '"
              + cron
              + "' UTC; checked "
              + initialDelay
              + " after startup, then every "
              + tick
              + "; a run missed while no replica was up starts at the next check";
      if (model.getAvailable()) {
        log.info(
            "Scout scheduled ({}), enrichment with {}/{}",
            when,
            model.getProvider(),
            model.getModel());
      } else {
        // Without a model the catalog still grows, but nothing is categorized beyond source
        // metadata.
        log.warn(
            "Scout scheduled ({}) WITHOUT an LLM: new resources are not categorized. "
                + "Start with the llm-nebius profile (and H2_NEBIUS_API_KEY) to enable enrichment.",
            when);
      }
    }

    /**
     * Every replica ticks (first shortly after startup, on the scheduler thread, so startup and
     * readiness are not delayed); the tick runs the Scout only when the cron time after the last
     * scheduled run has passed, which is decided in the database, so the replicas together run it
     * once per cron time whatever their start times. A tick that meets another run (an admin's, or
     * a replica's) is not lost: the next tick runs it. A run starts at most one tick after its cron
     * time. Collectors that should run less often have their own interval (HF trending 1 d, Tavily
     * 5 d, Tavily discovery and courses 7 d).
     */
    @Scheduled(
        initialDelayString = "${helper2.scout.initial-delay}",
        fixedDelayString = "${helper2.scout.tick}")
    void tick() {
      if (runner.tryRunScheduled(calendar, schedule) == ScheduledRun.BUSY) {
        log.info(
            "scheduled scout run deferred: another run is in progress; the next tick tries again");
      }
    }
  }
}
