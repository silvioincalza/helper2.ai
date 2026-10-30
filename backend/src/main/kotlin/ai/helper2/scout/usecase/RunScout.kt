package ai.helper2.scout.usecase

import ai.helper2.scout.CandidateReview
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.Collector
import ai.helper2.scout.EnrichmentBacklog
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.RunLock
import ai.helper2.scout.RunStatus
import ai.helper2.scout.RunSummary
import ai.helper2.scout.ScheduledRun
import ai.helper2.scout.ScheduledRunStore
import ai.helper2.scout.ScoutCursor
import ai.helper2.scout.ScoutStore
import ai.helper2.search.ResultMerger
import ai.helper2.search.ServedFrom
import ai.helper2.search.SourceContext
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import org.slf4j.LoggerFactory

/**
 * One enrichment budget shared by every collector and the backlog within a single Scout run. It counts model
 * calls: a resource found UNCHANGED gives its slot back, but stays attempted, so it is not checked twice in a run.
 */
private class EnrichmentBudget(private var remaining: Int) {
    val attempted = mutableSetOf<UUID>()
    private var stopped = false

    fun take(resourceId: UUID): Boolean {
        if (remaining <= 0 || resourceId in attempted) return false
        remaining--
        attempted += resourceId
        return true
    }

    /** The slot was not spent on a model call. */
    fun refund() {
        if (!stopped) remaining++
    }

    /** The model is down or its daily budget is spent: trying the rest of this run would only fail the same way. */
    fun stop() {
        stopped = true
        remaining = 0
    }

    val left: Int get() = remaining
}

/** A collector of a run and its record as saved when the run started. */
private class Planned(val collector: Collector, val summary: RunSummary)

/**
 * Scheduled, deterministic collection. Each collector runs in isolation: one failure never stops
 * the others. Hits are indexed; candidates go to the review list. Checkpoints advance only after a
 * complete pass, so runs are incremental and idempotent (re-running a pass re-upserts the same
 * records with the same fingerprints, and the review list opens nothing it has seen before).
 */
class RunScout(
    private val collectors: List<Collector>,
    private val store: ScoutStore,
    private val catalog: CatalogIndex,
    private val enrichment: EnrichResource?,
    private val clock: Clock,
    private val lock: RunLock,
    private val maxItemsPerRun: Int = 500,
    private val maxEnrichmentsPerRun: Int = 20,
    private val backlog: EnrichmentBacklog? = null,
    private val meters: MeterRegistry = SimpleMeterRegistry(),
    /** Where a started run collects; the default runs it on the caller's thread. */
    private val launcher: Executor = Executor { it.run() },
    // The observation's timer is the helper2.scout.run metric, so the default registry records into [meters].
    private val observations: ObservationRegistry = ObservationRegistry.create().also {
        it.observationConfig().observationHandler(DefaultMeterObservationHandler(meters))
    },
    /** The admin review list for the candidates a collector proposes; required as soon as one does. */
    private val review: CandidateReview? = null,
) {
    private val log = LoggerFactory.getLogger(RunScout::class.java)
    // Strong references: a gauge only holds its source object weakly.
    private val lastSuccess = ConcurrentHashMap<String, AtomicLong>()
    private val merger = ResultMerger()

    /**
     * Starts a run of every collector (or only [onlySourceId]) on the [launcher] and answers as soon as the run holds
     * the [RunLock]: with one record per collector, RUNNING (or SKIPPED when disabled), saved before collection begins.
     * The run then collects, then spends what is left of the enrichment budget on the backlog, so resources that
     * missed earlier runs (budget, LLM outage, transient failure) are not stranded. The lock is held until the run
     * ends. Returns null, without doing anything, while a run holds the lock on any replica.
     */
    fun tryStart(onlySourceId: String? = null): List<RunSummary>? {
        val started = CompletableFuture<List<RunSummary>?>()
        launcher.execute {
            try {
                lock.runExclusively(Supplier {
                    closeInterrupted()
                    val runs = plan(onlySourceId)
                    started.complete(runs.map { it.summary })
                    runAll(runs)
                })
                started.complete(null)
            } catch (e: Throwable) {
                log.warn("scout run failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}")
                started.completeExceptionally(e)
            }
        }
        val runs = try {
            started.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
        if (runs == null) log.info("scout run refused: a run is already in progress")
        return runs
    }

    /**
     * Closes as FAILED the run records still RUNNING: taken under the [RunLock], so no run is in progress and those
     * records belong to a replica that stopped mid-run. Returns false, changing nothing, while a run holds the lock.
     */
    fun closeInterruptedRuns(): Boolean = lock.runExclusively(Supplier { closeInterrupted(); true }) ?: false

    private fun closeInterrupted() {
        val closed = store.markInterrupted(clock.instant())
        if (closed > 0) log.warn("closed $closed scout run record(s) left RUNNING by a stopped replica")
    }

    /**
     * One tick of the schedule, on any replica: runs every collector when the last scheduled run ended at least
     * [every] ago (or none ever did), then records the end. Due check, run and record are under the run lock,
     * so replicas ticking in the same period run once; a tick that finds the lock taken changes nothing.
     */
    fun tryRunScheduled(every: Duration, schedule: ScheduledRunStore): ScheduledRun =
        lock.runExclusively(Supplier {
            val last = schedule.lastScheduledRunEnd()
            if (last != null && last.plus(every).isAfter(clock.instant())) {
                ScheduledRun.NOT_DUE
            } else {
                closeInterrupted()
                runAll(plan(null))
                schedule.scheduledRunEnded(clock.instant())
                ScheduledRun.RAN
            }
        }) ?: ScheduledRun.BUSY

    /** One record per selected collector: SKIPPED when disabled, otherwise RUNNING until its collection ends. */
    private fun plan(onlySourceId: String?): List<Planned> {
        val started = clock.instant()
        return collectors.filter { onlySourceId == null || it.descriptor.id == onlySourceId }.map { collector ->
            val d = collector.descriptor
            val summary = if (d.enabled) RunSummary(UUID.randomUUID(), d.id, RunStatus.RUNNING, started, null, 0, 0, 0, null)
            else RunSummary(UUID.randomUUID(), d.id, RunStatus.SKIPPED, started, started, 0, 0, 0, "Disabled in configuration")
            saveQuietly(summary)
            Planned(collector, summary)
        }
    }

    private fun runAll(planned: List<Planned>): List<RunSummary> =
        Observation.createNotStarted("helper2.scout.cycle", observations).observe(Supplier {
            val budget = EnrichmentBudget(maxEnrichmentsPerRun)
            val summaries = planned.map { run(it, budget) }
            drainBacklog(budget)
            summaries
        })

    private fun drainBacklog(budget: EnrichmentBudget) {
        if (enrichment == null || backlog == null || !enrichment.canEnrich() || budget.left <= 0) return
        val outcomes = try {
            // One at a time, so a slot given back by an UNCHANGED resource goes to the next one.
            backlog.pending(budget.left + budget.attempted.size).asSequence()
                .filter(budget::take)
                .mapNotNull { enrichQuietly(enrichment, it, budget) }
                .toList()
        } catch (e: RuntimeException) {
            log.warn("enrichment backlog failed: ${e.javaClass.simpleName}")
            return
        }
        if (outcomes.isNotEmpty()) {
            log.info("enrichment backlog: ${outcomes.size} attempted, ${outcomes.groupingBy { it }.eachCount()}")
        }
    }

    private fun run(planned: Planned, budget: EnrichmentBudget): RunSummary {
        val observation = Observation.createNotStarted("helper2.scout.run", observations)
            .lowCardinalityKeyValue("source", planned.collector.descriptor.id)
        return observation.observe(Supplier {
            val summary = if (planned.summary.status == RunStatus.SKIPPED) planned.summary
            else runCollector(planned.collector, planned.summary, budget, observation)
            observation.lowCardinalityKeyValue("status", summary.status.name.lowercase())
            record(summary)
            summary
        })
    }

    /**
     * One enrichment that cannot fail the run: model errors are already outcomes, so this catches the
     * rest (e.g. the database failing while material loads). null = not attempted; the backlog retries it.
     * LLM_UNAVAILABLE stops enrichment for the rest of the run: the provider is down or the budget is spent.
     * UNCHANGED made no model call, so its slot goes back to the budget.
     */
    private fun enrichQuietly(enrichment: EnrichResource, resourceId: UUID, budget: EnrichmentBudget): EnrichmentOutcome? = try {
        enrichment.enrich(resourceId).also {
            when (it) {
                EnrichmentOutcome.LLM_UNAVAILABLE -> budget.stop()
                EnrichmentOutcome.UNCHANGED -> budget.refund()
                else -> {}
            }
        }
    } catch (e: RuntimeException) {
        log.warn("enrichment of $resourceId failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}")
        null
    }

    /** Run history is a record, not a step: a store failure is logged and the collectors carry on. */
    private fun saveQuietly(summary: RunSummary) {
        try {
            store.save(summary)
        } catch (e: RuntimeException) {
            log.warn("saving scout run ${summary.id} for ${summary.sourceId} failed: ${e.javaClass.simpleName}")
        }
    }

    private fun record(summary: RunSummary) {
        if (summary.status != RunStatus.SUCCEEDED) return
        val source = summary.sourceId
        meters.counter("helper2.scout.items", "source", source, "kind", "seen").increment(summary.itemsSeen.toDouble())
        meters.counter("helper2.scout.items", "source", source, "kind", "changed").increment(summary.itemsChanged.toDouble())
        meters.counter("helper2.scout.items", "source", source, "kind", "queued").increment(summary.enrichmentsQueued.toDouble())
        val finished = (summary.finishedAt ?: summary.startedAt).epochSecond
        lastSuccess.computeIfAbsent(source) {
            AtomicLong().also { Gauge.builder("helper2.scout.last.success", it, AtomicLong::toDouble).tag("source", source).register(meters) }
        }.set(finished)
    }

    private fun runCollector(collector: Collector, running: RunSummary, budget: EnrichmentBudget, observation: Observation): RunSummary {
        val d = collector.descriptor
        val id = running.id
        val started = running.startedAt
        val summary = try {
            val cursor = store.cursor(d.id) ?: ScoutCursor(null, null, null)
            val passStartedAt = cursor.passStartedAt ?: started.toString()
            val batch = collector.collect(cursor.checkpoint, cursor.resumeCursor, maxItemsPerRun)
            if (batch.notDue) {
                return RunSummary(id, d.id, RunStatus.SKIPPED, started, clock.instant(), 0, 0, 0, "Not due yet (run interval)")
                    .also(::saveQuietly)
            }
            val merged = merger.merge(batch.hits, mapOf(d.id to SourceContext(d, ServedFrom.LIVE)))
            val changed = catalog.index(merged, d.id).distinct()
            if (batch.candidates.isNotEmpty()) {
                checkNotNull(review) { "${d.id} proposed candidates but no review list is configured" }.propose(d.id, batch.candidates)
            }
            store.saveCursor(
                d.id,
                if (batch.nextCursor != null) ScoutCursor(cursor.checkpoint, batch.nextCursor, passStartedAt)
                else ScoutCursor(if (batch.checkpoint != null) passStartedAt else cursor.checkpoint, null, null),
            )
            // From here the pass is indexed and its cursor saved: nothing below may turn the run into FAILED.
            var queued = 0
            var failed = 0
            if (enrichment != null) {
                val candidates = try {
                    if (backlog == null || changed.isEmpty()) changed else backlog.eligible(changed)
                } catch (e: RuntimeException) {
                    log.warn("enrichment eligibility for ${d.id} failed: ${e.javaClass.simpleName}")
                    emptyList()
                }
                for (resourceId in candidates) {
                    if (!budget.take(resourceId)) continue
                    when (enrichQuietly(enrichment, resourceId, budget)) {
                        null -> failed++
                        EnrichmentOutcome.UNCHANGED, EnrichmentOutcome.LLM_UNAVAILABLE -> {}
                        else -> queued++
                    }
                }
            }
            val note = listOfNotNull(
                "Stopped at item budget; next run resumes from the saved cursor".takeIf { batch.nextCursor != null },
                "$failed enrichment${if (failed == 1) "" else "s"} failed; the backlog retries ${if (failed == 1) "it" else "them"}"
                    .takeIf { failed > 0 },
            ).joinToString(". ").ifEmpty { null }
            RunSummary(id, d.id, RunStatus.SUCCEEDED, started, clock.instant(), batch.hits.size, changed.size, queued, note)
        } catch (e: RuntimeException) {
            // The message tells apart failures that share a class (timeout, HTTP status, malformed body).
            log.warn("scout run for ${d.id} failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}")
            observation.error(e)
            RunSummary(id, d.id, RunStatus.FAILED, started, clock.instant(), 0, 0, 0, "Collection failed: ${e.javaClass.simpleName}")
        }
        saveQuietly(summary)
        return summary
    }
}
