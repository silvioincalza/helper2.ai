package ai.helper2.scout.usecase

import ai.helper2.scout.Candidate
import ai.helper2.scout.CandidateReview
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.CollectedBatch
import ai.helper2.scout.Collector
import ai.helper2.scout.EnrichmentBacklog
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.KnownTargetProposals
import ai.helper2.scout.KnownTargets
import ai.helper2.scout.ListIndex
import ai.helper2.scout.ListText
import ai.helper2.scout.Proposal
import ai.helper2.scout.RunCalendar
import ai.helper2.scout.RunLock
import ai.helper2.scout.RunStatus
import ai.helper2.scout.RunSummary
import ai.helper2.scout.ScheduledRun
import ai.helper2.scout.ScheduledRunStore
import ai.helper2.scout.ScoutCursor
import ai.helper2.scout.ScoutStore
import ai.helper2.scout.Withdrawal
import ai.helper2.scout.WithdrawnRecords
import ai.helper2.scout.admission.Admission
import ai.helper2.scout.admission.AdmissionPolicy
import ai.helper2.scout.admission.ReviewReason
import ai.helper2.search.Refusal
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
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.IntSupplier
import java.util.function.Supplier
import org.slf4j.LoggerFactory

private class EnrichmentBudget(private var remaining: Int) {
  val attempted = mutableSetOf<UUID>()
  private var stopped = false

  fun take(resourceId: UUID): Boolean {
    if (remaining <= 0 || resourceId in attempted) return false
    remaining--
    attempted += resourceId
    return true
  }

  fun refund() {
    if (!stopped) remaining++
  }

  fun stop() {
    stopped = true
    remaining = 0
  }

  val left: Int
    get() = remaining
}

private class Planned(val collector: Collector, val summary: RunSummary)

private class EnrichmentCounts(val queued: Int, val failed: Int, val deferred: Int)

private const val ITEMS_DROPPED = "helper2.source.items.dropped"

class UnknownSource(val sourceId: String) : IllegalArgumentException("unknown source: $sourceId")

class RunScout(
  private val collectors: List<Collector>,
  private val store: ScoutStore,
  private val catalog: CatalogIndex,
  private val enrichment: EnrichResource?,
  private val clock: Clock,
  private val lock: RunLock,
  private val maxItemsPerRun: Int = 500,
  private val maxEnrichmentsPerRun: IntSupplier = IntSupplier { 20 },
  private val backlog: EnrichmentBacklog? = null,
  private val meters: MeterRegistry = SimpleMeterRegistry(),
  private val launcher: Executor = Executor { it.run() },
  private val observations: ObservationRegistry =
    ObservationRegistry.create().also {
      it.observationConfig().observationHandler(DefaultMeterObservationHandler(meters))
    },
  private val review: CandidateReview? = null,
  private val lists: ListIndex? = null,
  private val withdrawn: WithdrawnRecords? = null,
  private val knownTargets: KnownTargets? = null,
  private val admission: AdmissionPolicy = AdmissionPolicy(),
  private val targetProposals: KnownTargetProposals? = null,
) {
  private val log = LoggerFactory.getLogger(RunScout::class.java)
  private val lastSuccess = ConcurrentHashMap<String, AtomicLong>()
  private val merger = ResultMerger()

  fun tryStart(onlySourceId: String? = null): List<RunSummary>? {
    if (onlySourceId != null && collectors.none { it.descriptor.id == onlySourceId }) {
      throw UnknownSource(onlySourceId)
    }
    val started = CompletableFuture<List<RunSummary>?>()
    launcher.execute { runReportingStart(onlySourceId, started) }
    val runs = awaitStart(started)
    if (runs == null) log.info("scout run refused: a run is already in progress")
    return runs
  }

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

  private fun awaitStart(started: CompletableFuture<List<RunSummary>?>): List<RunSummary>? =
    try {
      started.join()
    } catch (e: CompletionException) {
      throw e.cause ?: e
    }

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

  fun tryRunScheduled(calendar: RunCalendar, schedule: ScheduledRunStore): ScheduledRun =
    lock.runExclusively(Supplier { runIfDue(calendar, schedule) }) ?: ScheduledRun.BUSY

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
          val budget = EnrichmentBudget(maxEnrichmentsPerRun.asInt)
          val summaries = planned.map { run(it, budget) }
          drainBacklog(budget)
          linkKnownTargetsQuietly()
          proposeKnownTargetsQuietly()
          summaries
        }
      )

  private fun linkKnownTargetsQuietly() {
    if (knownTargets == null) return
    val linked =
      try {
        knownTargets.linkOpenCandidates()
      } catch (e: RuntimeException) {
        log.warn("linking known relationship targets failed: ${e.javaClass.simpleName}")
        return
      }
    meters.counter("helper2.scout.targets.linked").increment(linked.toDouble())
    if (linked > 0)
      log.info("relationship candidates linked to a target now in the catalog: $linked")
  }

  private fun proposeKnownTargetsQuietly() {
    if (targetProposals == null) return
    val proposal =
      try {
        targetProposals.propose()
      } catch (e: RuntimeException) {
        log.warn("proposing known targets failed: ${e.javaClass.simpleName}")
        return
      }
    meters.counter("helper2.scout.targets.proposed").increment(proposal.opened.toDouble())
    meters.counter("helper2.scout.targets.held").increment(proposal.heldBack.toDouble())
    if (proposal.opened > 0 || proposal.heldBack > 0)
      log.info(
        "known targets sent to review: ${proposal.opened}, held back by the daily cap: ${proposal.heldBack}"
      )
  }

  private fun drainBacklog(budget: EnrichmentBudget) {
    if (enrichment == null || backlog == null || !enrichment.canEnrich() || budget.left <= 0) return
    val outcomes =
      try {
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
        log.warn(
          "scout run for ${running.sourceId} failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}"
        )
        observation.error(e)
        failedRun(running, e)
      }
    saveQuietly(summary)
    return summary
  }

  private fun indexable(d: SourceDescriptor, hits: List<SourceHit>): List<SourceHit> {
    val leftToReview = mutableMapOf<ReviewReason, Int>()
    val indexable = hits.filter { hit ->
      when (val decision = admission.decide(hit, d.id)) {
        Admission.Index -> true
        is Admission.Review -> false.also { leftToReview.merge(decision.reason, 1, Int::plus) }
        is Admission.Refuse -> false.also { countDropped(d.id, decision.reason) }
      }
    }
    if (leftToReview.isNotEmpty()) {
      log.info(
        "${d.id}: hits not indexed without a person: ${leftToReview.mapKeys { it.key.reason }}"
      )
    }
    return indexable.mapIndexed { place, hit ->
      if (hit.rank == place) hit else hit.withRank(place)
    }
  }

  private fun proposable(sourceId: String, candidates: List<Candidate>): List<Candidate> {
    val seenAt = clock.instant()
    return candidates.filter { candidate ->
      val decision = admission.decide(statedBy(sourceId, candidate, seenAt), sourceId)
      if (decision is Admission.Refuse) countDropped(sourceId, decision.reason)
      decision !is Admission.Refuse
    }
  }

  private fun statedBy(sourceId: String, candidate: Candidate, seenAt: Instant): SourceHit =
    candidate.signals.statedOn(
      SourceHit(
        sourceId = sourceId,
        externalId = candidate.url.value,
        url = URI.create(candidate.url.value),
        canonicalUrl = candidate.url,
        title = candidate.title,
        type = candidate.type,
        retrievedAt = seenAt,
      )
    )

  private fun countDropped(sourceId: String, refusal: Refusal) =
    meters.counter(ITEMS_DROPPED, "source", sourceId, "reason", refusal.reason).increment()

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
    val changed = index(d, indexable(d, batch.hits))
    indexListsQuietly(d.id, batch.lists)
    val proposal =
      proposeCandidates(d.id, proposable(d.id, batch.candidates), batch.newCandidatesPerDay)
    val cleaned = forgetWithdrawnQuietly(d.id, batch.withdrawn)
    if (cleaned != null)
      store.saveCursor(d.id, cursorAfter(cursor, batch, proposal, running.startedAt))
    else keepPassOpen(d.id, cursor, running.startedAt)
    val enriched = enrichChanged(d.id, changed, budget)
    return succeededRun(running, batch, changed.size, enriched, cleaned ?: Withdrawal.NONE)
  }

  private fun forgetWithdrawnQuietly(sourceId: String, externalIds: List<String>): Withdrawal? {
    if (withdrawn == null || externalIds.isEmpty()) return Withdrawal.NONE
    return try {
      withdrawn.forget(sourceId, externalIds).also {
        countItems(sourceId, "withdrawn", it.records)
        it.keptResources.forEach(::requestLaterRun)
        if (it.records > 0) {
          log.info(
            "$sourceId: removed ${it.records} withdrawn records, ${it.resources} resources left without records"
          )
        }
      }
    } catch (e: RuntimeException) {
      log.warn(
        "forgetting the records $sourceId withdrew failed, the next run reads them again: ${e.javaClass.simpleName}"
      )
      null
    }
  }

  private fun index(d: SourceDescriptor, hits: List<SourceHit>): List<UUID> {
    val merged = merger.merge(hits, mapOf(d.id to SourceContext(d, ServedFrom.LIVE)))
    return catalog.index(merged, d.id).distinct()
  }

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

  private fun proposeCandidates(
    sourceId: String,
    candidates: List<Candidate>,
    newPerDay: Int?,
  ): Proposal {
    if (candidates.isEmpty()) return Proposal(0)
    return checkNotNull(review) {
        "$sourceId proposed candidates but no review list is configured"
      }
      .propose(sourceId, candidates, newPerDay)
  }

  private fun keepPassOpen(sourceId: String, cursor: ScoutCursor, started: Instant) {
    if (cursor.passStartedAt != null) return
    try {
      store.saveCursor(sourceId, cursor.copy(passStartedAt = started.toString()))
    } catch (e: RuntimeException) {
      log.warn("keeping the pass of $sourceId open failed: ${e.javaClass.simpleName}")
    }
  }

  private fun cursorAfter(
    cursor: ScoutCursor,
    batch: CollectedBatch,
    proposal: Proposal,
    started: Instant,
  ): ScoutCursor {
    val passStartedAt = cursor.passStartedAt ?: started.toString()
    if (batch.nextCursor != null) {
      return ScoutCursor(cursor.checkpoint, batch.nextCursor, passStartedAt)
    }
    if (proposal.heldBack > 0) {
      return ScoutCursor.candidatesHeldBack(cursor.checkpoint, passStartedAt)
    }
    val checkpoint = if (batch.checkpoint != null) passStartedAt else cursor.checkpoint
    return ScoutCursor(checkpoint, null, null)
  }

  private fun enrichChanged(
    sourceId: String,
    changed: List<UUID>,
    budget: EnrichmentBudget,
  ): EnrichmentCounts {
    if (enrichment == null) return EnrichmentCounts(0, 0, 0)
    var queued = 0
    var failed = 0
    var deferred = 0
    for (resourceId in eligibleForEnrichment(sourceId, changed)) {
      if (resourceId in budget.attempted) continue
      if (!budget.take(resourceId)) {
        if (stillToJudge(enrichment, resourceId) && requestLaterRun(resourceId)) deferred++
        continue
      }
      when (enrichQuietly(enrichment, resourceId, budget)) {
        null -> {
          failed++
          requestLaterRun(resourceId)
        }
        EnrichmentOutcome.UNCHANGED,
        EnrichmentOutcome.LLM_UNAVAILABLE -> {}
        else -> queued++
      }
    }
    return EnrichmentCounts(queued, failed, deferred)
  }

  private fun stillToJudge(enrichment: EnrichResource, resourceId: UUID): Boolean =
    try {
      !enrichment.unchanged(resourceId)
    } catch (e: RuntimeException) {
      log.warn("checking the last run of $resourceId failed: ${e.javaClass.simpleName}")
      true
    }

  private fun requestLaterRun(resourceId: UUID): Boolean {
    if (backlog == null) return false
    return try {
      backlog.requestRun(resourceId)
      true
    } catch (e: RuntimeException) {
      log.warn("putting $resourceId back in the backlog failed: ${e.javaClass.simpleName}")
      false
    }
  }

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
    cleaned: Withdrawal = Withdrawal.NONE,
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
      successNote(batch, enriched, cleaned),
    )

  private fun successNote(
    batch: CollectedBatch,
    enriched: EnrichmentCounts,
    cleaned: Withdrawal,
  ): String? =
    listOfNotNull(
        "Stopped at item budget; next run resumes from the saved cursor"
          .takeIf { batch.nextCursor != null },
        "${enriched.failed} enrichment${if (enriched.failed == 1) "" else "s"} failed; the backlog retries ${if (enriched.failed == 1) "it" else "them"}"
          .takeIf { enriched.failed > 0 },
        "${enriched.deferred} changed resource${if (enriched.deferred == 1) " waits" else "s wait"} for the backlog (enrichment budget spent)"
          .takeIf { enriched.deferred > 0 },
        "Removed ${cleaned.records} record${if (cleaned.records == 1) "" else "s"} the source withdrew"
          .takeIf { cleaned.records > 0 },
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
