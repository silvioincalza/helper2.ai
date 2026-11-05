package ai.helper2.scout.usecase

import ai.helper2.scout.Candidate
import ai.helper2.scout.CandidateReview
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.CollectedBatch
import ai.helper2.scout.Collector
import ai.helper2.scout.EnrichmentBacklog
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.ListIndex
import ai.helper2.scout.ListText
import ai.helper2.scout.RunCalendar
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
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceHit
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import org.slf4j.LoggerFactory

/**
 * One enrichment budget shared by every collector and the backlog within a single Scout run. It
 * counts model calls: a resource found UNCHANGED gives its slot back, but stays attempted, so it is
 * not checked twice in a run.
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

  /**
   * The model is down or its daily budget is spent: trying the rest of this run would only fail the
   * same way.
   */
  fun stop() {
    stopped = true
    remaining = 0
  }

  val left: Int
    get() = remaining
}

/** A collector of a run and its record as saved when the run started. */
private class Planned(val collector: Collector, val summary: RunSummary)

/** What enriching the changed resources of one collector did. */
private class EnrichmentCounts(val queued: Int, val failed: Int)

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
  // The observation's timer is the helper2.scout.run metric, so the default registry records into
  // [meters].
  private val observations: ObservationRegistry =
    ObservationRegistry.create().also {
      it.observationConfig().observationHandler(DefaultMeterObservationHandler(meters))
    },
  /**
   * The admin review list for the candidates a collector proposes; required as soon as one does.
   */
  private val review: CandidateReview? = null,
  /** The search index of curated lists (ADR 0055); required as soon as a collector reads one. */
  private val lists: ListIndex? = null,
) {
  private val log = LoggerFactory.getLogger(RunScout::class.java)
  // Strong references: a gauge only holds its source object weakly.
  private val lastSuccess = ConcurrentHashMap<String, AtomicLong>()
  private val merger = ResultMerger()

  /**
   * Starts a run of every collector (or only [onlySourceId]) on the [launcher] and answers as soon
   * as the run holds the [RunLock]: with one record per collector, RUNNING (or SKIPPED when
   * disabled), saved before collection begins. The run then collects, then spends what is left of
   * the enrichment budget on the backlog, so resources that missed earlier runs (budget, LLM
   * outage, transient failure) are not stranded. The lock is held until the run ends. Returns null,
   * without doing anything, while a run holds the lock on any replica.
   */
  fun tryStart(onlySourceId: String? = null): List<RunSummary>? {
    val started = CompletableFuture<List<RunSummary>?>()
    launcher.execute { runReportingStart(onlySourceId, started) }
    val runs = awaitStart(started)
    if (runs == null) log.info("scout run refused: a run is already in progress")
    return runs
  }

  /**
   * Runs under the lock and completes [started] with the planned records as soon as they are saved;
   * with null when the lock is taken, exceptionally when the run fails before that.
   */
  private fun runReportingStart(
    onlySourceId: String?,
    started: CompletableFuture<List<RunSummary>?>,
  ) {
    try {
      lock.runExclusively(
        Supplier {
          closeInterrupted()
          val runs = plan(onlySourceId)
          started.complete(runs.map { it.summary })
          runAll(runs)
        }
      )
      started.complete(null)
    } catch (e: Throwable) {
      log.warn("scout run failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}")
      started.completeExceptionally(e)
    }
  }

  /** Waits for the run to start (or be refused), rethrowing what made it fail. */
  private fun awaitStart(started: CompletableFuture<List<RunSummary>?>): List<RunSummary>? =
    try {
      started.join()
    } catch (e: CompletionException) {
      throw e.cause ?: e
    }

  /**
   * Closes as FAILED the run records still RUNNING: taken under the [RunLock], so no run is in
   * progress and those records belong to a replica that stopped mid-run. Returns false, changing
   * nothing, while a run holds the lock.
   */
  fun closeInterruptedRuns(): Boolean =
    lock.runExclusively(
      Supplier {
        closeInterrupted()
        true
      }
    ) ?: false

  private fun closeInterrupted() {
    val closed = store.markInterrupted(clock.instant())
    if (closed > 0) log.warn("closed $closed scout run record(s) left RUNNING by a stopped replica")
  }

  /**
   * One tick of the schedule, on any replica: runs every collector when the [calendar] says a run
   * is due after the last scheduled one (or none ever ran), then records the end. A replica that
   * was down at the due time runs it at its next tick. Due check, run and record are under the run
   * lock, so replicas ticking in the same period run once; a tick that finds the lock taken changes
   * nothing.
   */
  fun tryRunScheduled(calendar: RunCalendar, schedule: ScheduledRunStore): ScheduledRun =
    lock.runExclusively(Supplier { runIfDue(calendar, schedule) }) ?: ScheduledRun.BUSY

  /** Called under the run lock. */
  private fun runIfDue(calendar: RunCalendar, schedule: ScheduledRunStore): ScheduledRun {
    val last = schedule.lastScheduledRunEnd()
    if (last != null && calendar.nextRunAfter(last).isAfter(clock.instant())) {
      return ScheduledRun.NOT_DUE
    }
    closeInterrupted()
    runAll(plan(null))
    schedule.scheduledRunEnded(clock.instant())
    return ScheduledRun.RAN
  }

  /**
   * One record per selected collector: SKIPPED when disabled, otherwise RUNNING until its
   * collection ends.
   */
  private fun plan(onlySourceId: String?): List<Planned> {
    val started = clock.instant()
    return collectors
      .filter { onlySourceId == null || it.descriptor.id == onlySourceId }
      .map { collector ->
        val summary = startRecord(collector.descriptor, started)
        saveQuietly(summary)
        Planned(collector, summary)
      }
  }

  private fun startRecord(d: SourceDescriptor, started: Instant): RunSummary {
    if (d.enabled) {
      return RunSummary(UUID.randomUUID(), d.id, RunStatus.RUNNING, started, null, 0, 0, 0, null)
    }
    return RunSummary(
      UUID.randomUUID(),
      d.id,
      RunStatus.SKIPPED,
      started,
      started,
      0,
      0,
      0,
      "Disabled in configuration",
    )
  }

  private fun runAll(planned: List<Planned>): List<RunSummary> =
    Observation.createNotStarted("helper2.scout.cycle", observations)
      .observe(
        Supplier {
          val budget = EnrichmentBudget(maxEnrichmentsPerRun)
          val summaries = planned.map { run(it, budget) }
          drainBacklog(budget)
          summaries
        }
      )

  private fun drainBacklog(budget: EnrichmentBudget) {
    if (enrichment == null || backlog == null || !enrichment.canEnrich() || budget.left <= 0) return
    val outcomes =
      try {
        // One at a time, so a slot given back by an UNCHANGED resource goes to the next one.
        backlog
          .pending(budget.left + budget.attempted.size)
          .asSequence()
          .filter(budget::take)
          .mapNotNull { enrichQuietly(enrichment, it, budget) }
          .toList()
      } catch (e: RuntimeException) {
        log.warn("enrichment backlog failed: ${e.javaClass.simpleName}")
        return
      }
    if (outcomes.isNotEmpty()) {
      log.info(
        "enrichment backlog: ${outcomes.size} attempted, ${outcomes.groupingBy { it }.eachCount()}"
      )
    }
  }

  private fun run(planned: Planned, budget: EnrichmentBudget): RunSummary {
    val observation =
      Observation.createNotStarted("helper2.scout.run", observations)
        .lowCardinalityKeyValue("source", planned.collector.descriptor.id)
    return observation.observe(
      Supplier {
        val summary =
          if (planned.summary.status == RunStatus.SKIPPED) planned.summary
          else runCollector(planned.collector, planned.summary, budget, observation)
        observation.lowCardinalityKeyValue("status", summary.status.name.lowercase())
        record(summary)
        summary
      }
    )
  }

  /**
   * One enrichment that cannot fail the run: model errors are already outcomes, so this catches the
   * rest (e.g. the database failing while material loads). null = not attempted; the backlog
   * retries it. LLM_UNAVAILABLE stops enrichment for the rest of the run: the provider is down or
   * the budget is spent. UNCHANGED made no model call, so its slot goes back to the budget.
   */
  private fun enrichQuietly(
    enrichment: EnrichResource,
    resourceId: UUID,
    budget: EnrichmentBudget,
  ): EnrichmentOutcome? =
    try {
      enrichment.enrich(resourceId).also {
        when (it) {
          EnrichmentOutcome.LLM_UNAVAILABLE -> budget.stop()
          EnrichmentOutcome.UNCHANGED -> budget.refund()
          else -> {}
        }
      }
    } catch (e: RuntimeException) {
      log.warn(
        "enrichment of $resourceId failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}"
      )
      null
    }

  /** Run history is a record, not a step: a store failure is logged and the collectors carry on. */
  private fun saveQuietly(summary: RunSummary) {
    try {
      store.save(summary)
    } catch (e: RuntimeException) {
      log.warn(
        "saving scout run ${summary.id} for ${summary.sourceId} failed: ${e.javaClass.simpleName}"
      )
    }
  }

  private fun record(summary: RunSummary) {
    if (summary.status != RunStatus.SUCCEEDED) return
    val source = summary.sourceId
    countItems(source, "seen", summary.itemsSeen)
    countItems(source, "changed", summary.itemsChanged)
    countItems(source, "queued", summary.enrichmentsQueued)
    lastSuccessOf(source).set((summary.finishedAt ?: summary.startedAt).epochSecond)
  }

  private fun countItems(source: String, kind: String, count: Int) =
    meters
      .counter("helper2.scout.items", "source", source, "kind", kind)
      .increment(count.toDouble())

  /** The gauge's value for [source], registered on first use. */
  private fun lastSuccessOf(source: String): AtomicLong =
    lastSuccess.computeIfAbsent(source) {
      AtomicLong().also {
        Gauge.builder("helper2.scout.last.success", it, AtomicLong::toDouble)
          .tag("source", source)
          .register(meters)
      }
    }

  private fun runCollector(
    collector: Collector,
    running: RunSummary,
    budget: EnrichmentBudget,
    observation: Observation,
  ): RunSummary {
    val summary =
      try {
        collect(collector, running, budget)
      } catch (e: RuntimeException) {
        // The message tells apart failures that share a class (timeout, HTTP status, malformed
        // body).
        log.warn(
          "scout run for ${running.sourceId} failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}"
        )
        observation.error(e)
        failedRun(running, e)
      }
    saveQuietly(summary)
    return summary
  }

  /**
   * One pass of [collector]: index the hits, propose the candidates, save the cursor, then enrich
   * what changed. Throws when the pass fails before its cursor is saved.
   */
  private fun collect(
    collector: Collector,
    running: RunSummary,
    budget: EnrichmentBudget,
  ): RunSummary {
    val d = collector.descriptor
    val cursor = store.cursor(d.id) ?: ScoutCursor(null, null, null)
    val batch =
      try {
        collector.collect(cursor, maxItemsPerRun)
      } catch (e: RuntimeException) {
        keepPassOpen(d.id, cursor, running.startedAt)
        throw e
      }
    if (batch.deferred) keepPassOpen(d.id, cursor, running.startedAt)
    if (batch.notDue) return notDueRun(running, batch.skipReason)
    val changed = index(d, batch.hits)
    indexListsQuietly(d.id, batch.lists)
    proposeCandidates(d.id, batch.candidates)
    store.saveCursor(d.id, cursorAfter(cursor, batch, running.startedAt))
    // From here the pass is indexed and its cursor saved: nothing below may turn the run into
    // FAILED.
    val enriched = enrichChanged(d.id, changed, budget)
    return succeededRun(running, batch, changed.size, enriched)
  }

  /** The distinct resources the [hits] changed. */
  private fun index(d: SourceDescriptor, hits: List<SourceHit>): List<UUID> {
    val merged = merger.merge(hits, mapOf(d.id to SourceContext(d, ServedFrom.LIVE)))
    return catalog.index(merged, d.id).distinct()
  }

  /**
   * After the hits, so the lists and their entries are in the catalog. Search inside lists only
   * helps recall: a failure is logged and the next pass rebuilds the index, the pass goes on.
   */
  private fun indexListsQuietly(sourceId: String, texts: List<ListText>) {
    if (texts.isEmpty()) return
    val index =
      checkNotNull(lists) { "$sourceId read curated lists but no list index is configured" }
    try {
      index.replace(texts)
    } catch (e: RuntimeException) {
      log.warn("list index for $sourceId failed: ${e.javaClass.simpleName}")
    }
  }

  private fun proposeCandidates(sourceId: String, candidates: List<Candidate>) {
    if (candidates.isEmpty()) return
    checkNotNull(review) { "$sourceId proposed candidates but no review list is configured" }
      .propose(sourceId, candidates)
  }

  /**
   * A pass that failed or was deferred stays open: its start is saved, so the next run tries it
   * again even when its interval says otherwise (a staggered first pass, ADR 0051), and its
   * checkpoint, once it completes, is this start. Losing this save only delays the retry.
   */
  private fun keepPassOpen(sourceId: String, cursor: ScoutCursor, started: Instant) {
    if (cursor.passStartedAt != null) return
    try {
      store.saveCursor(sourceId, cursor.copy(passStartedAt = started.toString()))
    } catch (e: RuntimeException) {
      log.warn("keeping the pass of $sourceId open failed: ${e.javaClass.simpleName}")
    }
  }

  /**
   * Where the next run starts: the resume cursor while the pass is unfinished; once it completes,
   * the pass start becomes the checkpoint (when the collector keeps one).
   */
  private fun cursorAfter(
    cursor: ScoutCursor,
    batch: CollectedBatch,
    started: Instant,
  ): ScoutCursor {
    val passStartedAt = cursor.passStartedAt ?: started.toString()
    if (batch.nextCursor != null) {
      return ScoutCursor(cursor.checkpoint, batch.nextCursor, passStartedAt)
    }
    val checkpoint = if (batch.checkpoint != null) passStartedAt else cursor.checkpoint
    return ScoutCursor(checkpoint, null, null)
  }

  private fun enrichChanged(
    sourceId: String,
    changed: List<UUID>,
    budget: EnrichmentBudget,
  ): EnrichmentCounts {
    if (enrichment == null) return EnrichmentCounts(0, 0)
    var queued = 0
    var failed = 0
    for (resourceId in eligibleForEnrichment(sourceId, changed)) {
      if (!budget.take(resourceId)) continue
      when (enrichQuietly(enrichment, resourceId, budget)) {
        null -> failed++
        EnrichmentOutcome.UNCHANGED,
        EnrichmentOutcome.LLM_UNAVAILABLE -> {}
        else -> queued++
      }
    }
    return EnrichmentCounts(queued, failed)
  }

  /** The [changed] resources the backlog still wants enriched; none when it cannot tell. */
  private fun eligibleForEnrichment(sourceId: String, changed: List<UUID>): List<UUID> =
    try {
      if (backlog == null || changed.isEmpty()) changed else backlog.eligible(changed)
    } catch (e: RuntimeException) {
      log.warn("enrichment eligibility for $sourceId failed: ${e.javaClass.simpleName}")
      emptyList()
    }

  private fun notDueRun(running: RunSummary, reason: String?) =
    RunSummary(
      running.id,
      running.sourceId,
      RunStatus.SKIPPED,
      running.startedAt,
      clock.instant(),
      0,
      0,
      0,
      reason ?: "Not due yet (run interval)",
    )

  private fun succeededRun(
    running: RunSummary,
    batch: CollectedBatch,
    changed: Int,
    enriched: EnrichmentCounts,
  ) =
    RunSummary(
      running.id,
      running.sourceId,
      RunStatus.SUCCEEDED,
      running.startedAt,
      clock.instant(),
      batch.hits.size,
      changed,
      enriched.queued,
      successNote(batch, enriched.failed),
    )

  private fun successNote(batch: CollectedBatch, failed: Int): String? =
    listOfNotNull(
        "Stopped at item budget; next run resumes from the saved cursor"
          .takeIf { batch.nextCursor != null },
        "$failed enrichment${if (failed == 1) "" else "s"} failed; the backlog retries ${if (failed == 1) "it" else "them"}"
          .takeIf { failed > 0 },
      )
      .joinToString(". ")
      .ifEmpty { null }

  private fun failedRun(running: RunSummary, e: RuntimeException) =
    RunSummary(
      running.id,
      running.sourceId,
      RunStatus.FAILED,
      running.startedAt,
      clock.instant(),
      0,
      0,
      0,
      "Collection failed: ${e.javaClass.simpleName}",
    )
}
