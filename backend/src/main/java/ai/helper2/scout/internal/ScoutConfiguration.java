package ai.helper2.scout.internal;

import ai.helper2.catalog.usecase.IndexResources;
import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CandidateReview;
import ai.helper2.scout.CatalogIndex;
import ai.helper2.scout.Collector;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentProposal;
import ai.helper2.scout.EnrichmentStore;
import ai.helper2.scout.EvidenceValidator;
import ai.helper2.scout.ExtraEvidence;
import ai.helper2.scout.GapBudget;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.scout.RunLock;
import ai.helper2.scout.ScheduledRun;
import ai.helper2.scout.ScheduledRunStore;
import ai.helper2.scout.ScoutStore;
import ai.helper2.scout.config.ScoutProperties;
import ai.helper2.scout.usecase.EnrichResource;
import ai.helper2.scout.usecase.RunScout;
import ai.helper2.scout.usecase.StartScoutRun;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
      ScoutProperties scout) {
    int maxTokens = scout.enrichMaxTokens();
    int dailyCalls = scout.dailyEnrichmentCalls();
    ChatModel model = chatModel.getIfAvailable();
    if (!llm.usable(model) || !llm.hasReasoningModel()) {
      log.info("No LLM configured: enrichment disabled, deterministic source metadata only");
      return new UnavailableModel();
    }
    DailyBudget budget = new DailyBudget(dailyCalls, clock, "enrichment", budgetCounter);
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

  @Bean
  CatalogIndex catalogIndex(IndexResources indexResources) {
    return indexResources::index;
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
      MeterRegistry meters,
      ObservationRegistry observations,
      ObjectProvider<ExtraEvidence> extraEvidence,
      CallCounter budgetCounter,
      ScoutProperties scout) {
    int maxItemsPerRun = scout.maxItemsPerRun();
    int maxEnrichmentsPerRun = scout.maxEnrichmentsPerRun();
    int gapDailyCalls = scout.gapDailyCalls();
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
            gapBudget(gapDailyCalls, clock, budgetCounter, meters)),
        clock,
        new ClusterRunLock(scoutRunLock),
        maxItemsPerRun,
        maxEnrichmentsPerRun,
        backlog,
        meters,
        RUN_THREADS,
        observations,
        review);
  }

  /**
   * The gap round's own daily cap (ADR 0043), counted like the others so a restart does not reset
   * the day. Its calls also count on the enrichment cap, so this is a share of it, not more. 0
   * turns the gap round off.
   */
  static GapBudget gapBudget(
      int dailyCalls, Clock clock, CallCounter counter, MeterRegistry meters) {
    if (dailyCalls <= 0) {
      return new GapBudget() {
        @Override
        public boolean tryAcquire() {
          return false;
        }

        @Override
        public boolean hasRoom() {
          return false;
        }
      };
    }
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
  StartScoutRun startScoutRun(RunScout scoutRunner, AuditTrail audit) {
    return new StartScoutRun(scoutRunner, audit);
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

  @Component
  @ConditionalOnProperty(name = "helper2.scout.enabled", havingValue = "true")
  static class ScoutSchedule {
    private final RunScout runner;
    private final ScheduledRunStore schedule;
    private final Duration every;

    ScoutSchedule(
        RunScout runner, ScheduledRunStore schedule, EnrichmentModel model, ScoutProperties scout) {
      this.runner = runner;
      this.schedule = schedule;
      this.every = scout.every();
      Duration initialDelay = scout.initialDelay();
      Duration tick = scout.tick();
      String when =
          "checked "
              + initialDelay
              + " after startup, then every "
              + tick
              + "; runs when the last "
              + "scheduled run ended "
              + every
              + " ago";
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
     * readiness are not delayed); the tick runs the Scout only when it is due, which is decided in
     * the database, so the replicas together run it once per period whatever their start times. A
     * tick that meets another run (an admin's, or a replica's) is not lost: the next tick runs it.
     * Collectors that should run less often have their own interval (MCP Registry 1 h, HF trending
     * 1 d, Tavily 5 d).
     */
    @Scheduled(
        initialDelayString = "${helper2.scout.initial-delay}",
        fixedDelayString = "${helper2.scout.tick}")
    void tick() {
      if (runner.tryRunScheduled(every, schedule) == ScheduledRun.BUSY) {
        log.info(
            "scheduled scout run deferred: another run is in progress; the next tick tries again");
      }
    }
  }
}
