package ai.helper2.scout.internal;

import ai.helper2.catalog.ResourceCatalog;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.scout.CatalogIndex;
import ai.helper2.scout.Collector;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentProposal;
import ai.helper2.scout.usecase.EnrichResource;
import ai.helper2.scout.EnrichmentStore;
import ai.helper2.scout.EvidenceValidator;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.scout.RunLock;
import ai.helper2.scout.ScheduledRun;
import ai.helper2.scout.ScheduledRunStore;
import ai.helper2.scout.usecase.RunScout;
import ai.helper2.scout.ScoutStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration(proxyBeanMethods = false)
class ScoutConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ScoutConfiguration.class);

    /**
     * Uses the reasoning model when one is configured (spring.ai.model.chat, helper2.llm.reasoning-model);
     * otherwise reports LLM_UNAVAILABLE. Its own daily cap, apart from the guide's: the Scout runs on a
     * schedule and must not spend the credits visitors need.
     */
    @Bean
    EnrichmentModel enrichmentModel(ObjectProvider<ChatModel> chatModel, LlmSettings llm, Clock clock, CallCounter budgetCounter,
                                    ModelCallMetrics metrics, MeterRegistry meters,
                                    @Value("${helper2.llm.enrich-max-tokens:" + SpringAiEnrichmentModel.DEFAULT_MAX_TOKENS + "}") int maxTokens,
                                    @Value("${helper2.scout.daily-enrichment-calls:300}") int dailyCalls) {
        ChatModel model = chatModel.getIfAvailable();
        if (!llm.usable(model) || !llm.hasReasoningModel()) {
            log.info("No LLM configured: enrichment disabled, deterministic source metadata only");
            return new UnavailableModel();
        }
        DailyBudget budget = new DailyBudget(dailyCalls, clock, "enrichment", budgetCounter);
        Gauge.builder("helper2.scout.enrichment.budget.remaining", budget, DailyBudget::remaining)
            .description("Scout enrichment model calls left under today's cap (UTC day)")
            .register(meters);
        // Recorded on every enrichment run, so provenance says which provider actually produced a proposal.
        log.info("LLM enrichment enabled: provider={}, model={}, maxTokens={}, dailyCalls={}",
            llm.provider(), llm.reasoningModel(), maxTokens, dailyCalls);
        return new SpringAiEnrichmentModel(model, llm.provider(), llm.reasoningModel(), maxTokens, budget, metrics);
    }

    @Bean
    CatalogIndex catalogIndex(ResourceCatalog catalog) {
        return (results, sourceId) -> {
            List<UUID> changedRecords = catalog.index(results, sourceId);
            return catalog.resourcesOfRecords(changedRecords);
        };
    }

    /** Collectors in one run, worst case: HTTP timeouts of 10-15 s and a few classifier calls; measured in minutes. */
    static final Duration COLLECTORS_WORST_CASE = Duration.ofHours(1);

    /**
     * Held by every Scout run, scheduled or from the admin button, on whichever replica. At most: the collectors plus
     * every enrichment of the run timing out on every attempt (prod: 1 h + 200 x 40 s x 1 = about 3 h 15 min); a
     * longer lock only matters when a pod dies mid-run. At least: zero, so the admin button works right after a run.
     */
    @Bean
    ClusterLock scoutRunLock(LockingTaskExecutor locks,
                             @Value("${helper2.scout.max-enrichments-per-run:20}") int maxEnrichmentsPerRun,
                             @Value("${spring.ai.openai.chat.timeout:40s}") Duration modelTimeout,
                             @Value("${spring.ai.openai.chat.max-retries:0}") int modelRetries) {
        Duration enrichments = modelTimeout.multipliedBy((long) maxEnrichmentsPerRun * (modelRetries + 1));
        ClusterLock lock = new ClusterLock(locks, "scout-run", COLLECTORS_WORST_CASE.plus(enrichments), Duration.ZERO);
        log.info("Scout run lock '{}' held at most {}", lock.name(), lock.atMostFor());
        return lock;
    }

    @Bean
    RunScout scoutRunner(List<Collector> collectors, ScoutStore store, CatalogIndex index, EnrichmentModel model,
                            EnrichmentStore enrichmentStore, EnrichmentBacklog backlog, Clock clock, ClusterLock scoutRunLock,
                            MeterRegistry meters, ObservationRegistry observations,
                            @Value("${helper2.scout.max-items-per-run:500}") int maxItemsPerRun,
                            @Value("${helper2.scout.max-enrichments-per-run:20}") int maxEnrichmentsPerRun) {
        return new RunScout(collectors, store, index, new EnrichResource(model, enrichmentStore, clock,
            new EvidenceValidator(), meters), clock, new ClusterRunLock(scoutRunLock), maxItemsPerRun, maxEnrichmentsPerRun,
            backlog, meters, observations);
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

        ScoutSchedule(RunScout runner, ScheduledRunStore schedule, EnrichmentModel model,
                      @Value("${helper2.scout.initial-delay}") Duration initialDelay,
                      @Value("${helper2.scout.tick}") Duration tick,
                      @Value("${helper2.scout.every}") Duration every) {
            this.runner = runner;
            this.schedule = schedule;
            this.every = every;
            String when = "checked " + initialDelay + " after startup, then every " + tick + "; runs when the last "
                + "scheduled run ended " + every + " ago";
            if (model.getAvailable()) {
                log.info("Scout scheduled ({}), enrichment with {}/{}", when, model.getProvider(), model.getModel());
            } else {
                // Without a model the catalog still grows, but nothing is categorized beyond source metadata.
                log.warn("Scout scheduled ({}) WITHOUT an LLM: new resources are not categorized. "
                    + "Start with the llm-nebius profile (and H2_NEBIUS_API_KEY) to enable enrichment.", when);
            }
        }

        /**
         * Every replica ticks (first shortly after startup, on the scheduler thread, so startup and readiness are
         * not delayed); the tick runs the Scout only when it is due, which is decided in the database, so the
         * replicas together run it once per period whatever their start times. A tick that meets another run
         * (an admin's, or a replica's) is not lost: the next tick runs it. Collectors that should run less often
         * have their own interval (MCP Registry 1 h, HF trending 1 d, Tavily 5 d).
         */
        @Scheduled(initialDelayString = "${helper2.scout.initial-delay}", fixedDelayString = "${helper2.scout.tick}")
        void tick() {
            if (runner.tryRunScheduled(every, schedule) == ScheduledRun.BUSY) {
                log.info("scheduled scout run deferred: another run is in progress; the next tick tries again");
            }
        }
    }
}
