package ai.searchavo.scout.usecase

import ai.searchavo.scout.Candidate
import ai.searchavo.scout.CandidateReview
import ai.searchavo.scout.CandidateSignals
import ai.searchavo.scout.CatalogIndex
import ai.searchavo.scout.Citation
import ai.searchavo.scout.CollectedBatch
import ai.searchavo.scout.Collector
import ai.searchavo.scout.EnrichmentBacklog
import ai.searchavo.scout.EnrichmentInput
import ai.searchavo.scout.EnrichmentMaterial
import ai.searchavo.scout.EnrichmentModel
import ai.searchavo.scout.EnrichmentOutcome
import ai.searchavo.scout.EnrichmentProposal
import ai.searchavo.scout.EnrichmentStore
import ai.searchavo.scout.ListEntryText
import ai.searchavo.scout.ListIndex
import ai.searchavo.scout.ListText
import ai.searchavo.scout.LlmUnavailable
import ai.searchavo.scout.Material
import ai.searchavo.scout.ModelAnswerInvalid
import ai.searchavo.scout.Proposal
import ai.searchavo.scout.ProposedField
import ai.searchavo.scout.ProposedRelationship
import ai.searchavo.scout.RunLock
import ai.searchavo.scout.RunStatus
import ai.searchavo.scout.RunSummary
import ai.searchavo.scout.ScoutCursor
import ai.searchavo.scout.ScoutStore
import ai.searchavo.scout.ValidationResult
import ai.searchavo.scout.Withdrawal
import ai.searchavo.scout.WithdrawnRecords
import ai.searchavo.scout.admission.AdmissionFixtures
import ai.searchavo.search.NOW
import ai.searchavo.search.PopularityKind
import ai.searchavo.search.PopularityMetric
import ai.searchavo.search.SourceDescriptor
import ai.searchavo.search.SourceFailure
import ai.searchavo.search.SourceHit
import ai.searchavo.search.descriptor
import ai.searchavo.search.hit
import ai.searchavo.shared.CanonicalUrl
import ai.searchavo.shared.DateFact
import ai.searchavo.shared.ResourceType
import ai.searchavo.testing.MovableClock
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

private val NO_LOCK =
  object : RunLock {
    override fun <T : Any> runExclusively(task: Supplier<T>): T? = task.get()
  }

private val EVIDENCE_ID = UUID.randomUUID()

private const val SOURCE_TEXT = "Postgres MCP server. Works with Claude Desktop and Cursor."

private fun material(title: String = "x") =
  EnrichmentMaterial(
    EnrichmentInput(
      title,
      listOf(Material(EVIDENCE_ID, URI("https://x.example"), NOW, SOURCE_TEXT)),
    ),
    "fp",
  )

private fun cited(field: String, value: String, excerpt: String) =
  ProposedField(field, value, listOf(Citation(EVIDENCE_ID.toString(), excerpt)))

private val STATED = cited("platforms", "desktop", "Works with Claude Desktop and Cursor")

private val INVENTED = cited("pricing", "FREE", "Completely free forever")

class RunScoutTest {

  private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
  private val history = MemStore()

  private fun RunScout.runNow(): List<RunSummary>? =
    tryStart()?.map { history.runs.getValue(it.id) }

  private class MemStore : ScoutStore {
    val cursors = mutableMapOf<String, ScoutCursor>()
    val runs = linkedMapOf<UUID, RunSummary>()

    override fun cursor(sourceId: String) = cursors[sourceId]

    override fun saveCursor(sourceId: String, cursor: ScoutCursor) {
      cursors[sourceId] = cursor
    }

    override fun save(run: RunSummary) {
      runs[run.id] = run
    }

    override fun recent(limit: Int) = runs.values.toList().takeLast(limit)

    override fun markInterrupted(at: Instant): Int {
      val open = runs.values.filter { it.status == RunStatus.RUNNING }
      open.forEach {
        runs[it.id] = it.copy(status = RunStatus.FAILED, finishedAt = at, message = "Interrupted")
      }
      return open.size
    }
  }

  private class FakeCollector(
    override val descriptor: SourceDescriptor,
    val every: Duration = Duration.ZERO,
    val behaviour: (String?) -> CollectedBatch,
  ) : Collector {
    override fun runEvery(): Duration = every

    val seenCheckpoints = mutableListOf<String?>()

    override fun collect(
      checkpoint: String?,
      resumeCursor: String?,
      maxItems: Int,
    ): CollectedBatch {
      seenCheckpoints += checkpoint
      return behaviour(checkpoint)
    }
  }

  private open class FakeEnrichmentStore : EnrichmentStore {
    override fun load(resourceId: UUID): EnrichmentMaterial? = material()

    override fun lastFingerprint(resourceId: UUID): String? = null

    override fun resourcesTitled(titles: Collection<String>, resourceId: UUID): Map<String, UUID> =
      emptyMap()

    override fun save(
      resourceId: UUID,
      model: EnrichmentModel,
      inputFingerprint: String,
      startedAt: java.time.Instant,
      result: ValidationResult?,
      outcome: EnrichmentOutcome,
      failure: String?,
      extraEvidence: String?,
    ) {}
  }

  private val changedIds = mutableListOf<UUID>()
  private val catalog = CatalogIndex { results, _ ->
    results.map { it.id.value }.also { changedIds += it }
  }

  @Test
  fun `one failing collector does not stop the others`() {
    val bad = FakeCollector(descriptor("bad")) { throw IllegalStateException("boom") }
    val good =
      FakeCollector(descriptor("good")) {
        CollectedBatch(
          listOf(hit("good", "https://github.com/a/x", type = ResourceType.MCP_SERVER)),
          null,
          "cp1",
        )
      }
    val runs = RunScout(listOf(bad, good), history, catalog, null, clock, NO_LOCK).runNow()!!
    assertEquals(listOf(RunStatus.FAILED, RunStatus.SUCCEEDED), runs.map { it.status })
    assertEquals(1, runs[1].itemsSeen)
  }

  @Test
  fun `a run ends by linking the candidates whose target is now in the catalog`() {
    val meters = SimpleMeterRegistry()
    var asked = 0
    val good = FakeCollector(descriptor("good")) { CollectedBatch(emptyList(), null, "cp1") }
    RunScout(
        listOf(good),
        history,
        catalog,
        null,
        clock,
        NO_LOCK,
        meters = meters,
        knownTargets = { 3.also { asked++ } },
      )
      .runNow()!!
    assertEquals(1, asked)
    assertEquals(3.0, meters.counter("searchavo.scout.targets.linked").count())
  }

  @Test
  fun `a run still succeeds when linking known targets fails`() {
    val good = FakeCollector(descriptor("good")) { CollectedBatch(emptyList(), null, "cp1") }
    val runs =
      RunScout(
          listOf(good),
          history,
          catalog,
          null,
          clock,
          NO_LOCK,
          knownTargets = { throw IllegalStateException("database is down") },
        )
        .runNow()!!
    assertEquals(listOf(RunStatus.SUCCEEDED), runs.map { it.status })
  }

  @Test
  fun `a run ends by proposing the known targets its rules find`() {
    val meters = SimpleMeterRegistry()
    var asked = 0
    val good = FakeCollector(descriptor("good")) { CollectedBatch(emptyList(), null, "cp1") }
    RunScout(
        listOf(good),
        history,
        catalog,
        null,
        clock,
        NO_LOCK,
        meters = meters,
        targetProposals = { Proposal(2, 4).also { asked++ } },
      )
      .runNow()!!
    assertEquals(1, asked)
    assertEquals(2.0, meters.counter("searchavo.scout.targets.proposed").count())
    assertEquals(4.0, meters.counter("searchavo.scout.targets.held").count())
  }

  @Test
  fun `a run still succeeds when proposing known targets fails`() {
    val good = FakeCollector(descriptor("good")) { CollectedBatch(emptyList(), null, "cp1") }
    val runs =
      RunScout(
          listOf(good),
          history,
          catalog,
          null,
          clock,
          NO_LOCK,
          targetProposals = { throw IllegalStateException("database is down") },
        )
        .runNow()!!
    assertEquals(listOf(RunStatus.SUCCEEDED), runs.map { it.status })
  }

  @Test
  fun `a failed run logs the failure's message, not only its class`() {
    val logger = LoggerFactory.getLogger(RunScout::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    logger.addAppender(appender)
    try {
      val c =
        FakeCollector(descriptor("mcp")) {
          throw SourceFailure.Unavailable("network error or timeout")
        }
      RunScout(listOf(c), history, catalog, null, clock, NO_LOCK).runNow()!!
    } finally {
      logger.detachAppender(appender)
    }
    val logged = appender.list.map { it.formattedMessage }
    assertEquals(listOf("scout run for mcp failed: Unavailable: network error or timeout"), logged)
  }

  @Test
  fun `run timer is tagged by source, status and error class, never the message`() {
    val meters = SimpleMeterRegistry()
    val bad = FakeCollector(descriptor("bad")) { throw IllegalStateException("boom") }
    val good =
      FakeCollector(descriptor("good")) {
        CollectedBatch(listOf(hit("good", "https://github.com/a/x")), null, "cp")
      }
    RunScout(listOf(bad, good), history, catalog, null, clock, NO_LOCK, meters = meters).runNow()!!
    fun count(source: String, status: String, error: String) =
      meters
        .get("searchavo.scout.run")
        .tags("source", source, "status", status, "error", error)
        .timer()
        .count()
    assertEquals(1, count("bad", "failed", "IllegalStateException"))
    assertEquals(1, count("good", "succeeded", "none"))
  }

  @Test
  fun `failed, succeeded and skipped runs all reach the Prometheus scrape`() {
    val meters = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val bad = FakeCollector(descriptor("bad")) { throw IllegalStateException("boom") }
    val good =
      FakeCollector(descriptor("good")) {
        CollectedBatch(listOf(hit("good", "https://github.com/a/x")), null, "cp")
      }
    val idle = FakeCollector(descriptor("idle")) { CollectedBatch.notDue() }
    RunScout(listOf(bad, good, idle), history, catalog, null, clock, NO_LOCK, meters = meters)
      .runNow()!!
    val runs =
      meters.scrape().lines().filter { it.startsWith("searchavo_scout_run_seconds_count{") }
    fun has(vararg tags: String) = runs.any { line -> tags.all { line.contains(it) } }
    assertEquals(3, runs.size, runs.joinToString("\n"))
    assert(has("source=\"bad\"", "status=\"failed\"", "error=\"IllegalStateException\""))
    assert(has("source=\"good\"", "status=\"succeeded\"", "error=\"none\""))
    assert(has("source=\"idle\"", "status=\"skipped\"", "error=\"none\""))
    assert(meters.scrape().contains("searchavo_scout_cycle_seconds_count"))
  }

  @Test
  fun `items counters follow succeeded runs only`() {
    val meters = SimpleMeterRegistry()
    val bad = FakeCollector(descriptor("bad")) { throw IllegalStateException("boom") }
    val hits = (0 until 2).map { hit("good", "https://github.com/a/r$it") }
    val good = FakeCollector(descriptor("good")) { CollectedBatch(hits, null, "cp") }
    RunScout(listOf(bad, good), history, catalog, null, clock, NO_LOCK, meters = meters).runNow()!!
    fun items(kind: String) =
      meters.get("searchavo.scout.items").tags("source", "good", "kind", kind).counter().count()
    assertEquals(2.0, items("seen"))
    assertEquals(2.0, items("changed"))
    assertEquals(0.0, items("queued"))
    assertNull(meters.find("searchavo.scout.items").tag("source", "bad").counter())
  }

  @Test
  fun `a run counts each enrichment as what became of it, in its summary and in its counters`() {
    val answers = listOf("validated", "partial", "failed", "no material", "store down", "no slot")
    val saved = mutableListOf<EnrichmentOutcome>()
    val store =
      object : FakeEnrichmentStore() {
        override fun load(resourceId: UUID): EnrichmentMaterial? =
          when (val answer = answers[changedIds.indexOf(resourceId)]) {
            "no material" -> null
            "store down" -> throw IllegalStateException("connection reset")
            else -> material(answer)
          }

        override fun save(
          resourceId: UUID,
          model: EnrichmentModel,
          inputFingerprint: String,
          startedAt: java.time.Instant,
          result: ValidationResult?,
          outcome: EnrichmentOutcome,
          failure: String?,
          extraEvidence: String?,
        ) {
          saved += outcome
        }
      }
    val model =
      object : EnrichmentModel {
        override val provider = "fake"
        override val model = "m"
        override val promptVersion = "enrich-v3"

        override fun propose(input: EnrichmentInput): EnrichmentProposal =
          when (input.resourceTitle) {
            "validated" -> EnrichmentProposal(fields = listOf(STATED))
            "partial" -> EnrichmentProposal(fields = listOf(STATED, INVENTED))
            else -> throw ModelAnswerInvalid("answer contains no JSON object")
          }
      }
    val meters = SimpleMeterRegistry()
    val requested = mutableListOf<UUID>()
    val hits = answers.indices.map { hit("mcp", "https://github.com/a/r$it") }
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }

    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          EnrichResource(model, store, clock),
          clock,
          NO_LOCK,
          maxEnrichmentsPerRun = { 5 },
          backlog = backlog(requested = requested) { emptyList() },
          meters = meters,
        )
        .runNow()!!
        .single()

    assertEquals(
      listOf(EnrichmentOutcome.VALIDATED, EnrichmentOutcome.PARTIAL, EnrichmentOutcome.FAILED),
      saved,
    )
    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(6, run.itemsSeen)
    assertEquals(6, run.itemsChanged)
    assertEquals(2, run.enrichmentsQueued)
    assertEquals(
      "2 enrichments failed; the backlog retries them. " +
        "1 enrichment rejected; nothing was kept. " +
        "1 changed resource waits for the backlog (enrichment budget spent)",
      run.message,
    )
    assertEquals(
      changedIds.drop(4),
      requested,
      "a failure the enrichment recorded is retried by the backlog rule, without a request",
    )
    fun items(kind: String) =
      meters.get("searchavo.scout.items").tags("source", "mcp", "kind", kind).counter().count()
    assertEquals(6.0, items("seen"))
    assertEquals(6.0, items("changed"))
    assertEquals(2.0, items("queued"))
    assertEquals(2.0, items("failed"))
    assertEquals(1.0, items("rejected"))
  }

  @Test
  fun `the run interval of every collector is a gauge from the start, before any run`() {
    val meters = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val weekly = FakeCollector(descriptor("weekly"), Duration.ofDays(7)) { CollectedBatch.notDue() }
    val hourly =
      FakeCollector(descriptor("hourly"), Duration.ofHours(1)) { CollectedBatch.notDue() }
    RunScout(listOf(weekly, hourly), history, catalog, null, clock, NO_LOCK, meters = meters)
    fun interval(source: String) =
      meters.get("searchavo.scout.interval").tag("source", source).gauge().value()
    assertEquals(7 * 86400.0, interval("weekly"))
    assertEquals(3600.0, interval("hourly"))
    assert(meters.scrape().contains("searchavo_scout_interval_seconds{source=\"weekly\"} 604800"))
  }

  @Test
  fun `every enrichment attempt is counted by outcome`() {
    val meters = SimpleMeterRegistry()
    val store =
      object : FakeEnrichmentStore() {
        override fun save(
          resourceId: UUID,
          model: EnrichmentModel,
          inputFingerprint: String,
          startedAt: java.time.Instant,
          result: ValidationResult?,
          outcome: EnrichmentOutcome,
          failure: String?,
          extraEvidence: String?,
        ) {}
      }
    val model =
      object : EnrichmentModel {
        override val provider = "none"
        override val model = "none"
        override val promptVersion = "enrich-v3"

        override fun propose(input: EnrichmentInput): EnrichmentProposal =
          throw LlmUnavailable("no model")
      }
    val service = EnrichResource(model, store, clock, meters = meters)
    service.enrich(UUID.randomUUID())
    service.enrich(UUID.randomUUID())
    assertEquals(
      2.0,
      meters.get("searchavo.scout.enrichment").tag("outcome", "LLM_UNAVAILABLE").counter().count(),
    )
  }

  private class RecordingWithdrawal(
    val kept: List<UUID> = emptyList(),
    val failure: RuntimeException? = null,
  ) : WithdrawnRecords {
    val calls = mutableListOf<Pair<String, List<String>>>()

    override fun forget(sourceId: String, externalIds: List<String>): Withdrawal {
      failure?.let { throw it }
      calls += sourceId to externalIds
      return Withdrawal(externalIds.size, 1, kept)
    }
  }

  @Test
  fun `the hits a collector writes for another of its sources are indexed under that source too`() {
    val calls = mutableListOf<Pair<String, List<String>>>()
    val index = CatalogIndex { results, sourceId ->
      calls += sourceId to results.flatMap { r -> r.hits.map { it.sourceId } }
      results.map { it.id.value }
    }
    val lists = descriptor("lists")
    val c =
      object : Collector {
        override val descriptor = descriptor("awesome")

        override fun runEvery(): Duration = Duration.ZERO

        override fun alsoWrites() = listOf(lists)

        override fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int) =
          CollectedBatch(
            listOf(
              hit("awesome", "https://github.com/a/x", type = ResourceType.MCP_SERVER),
              hit("lists", "https://acme.dev/best-ai-tools", type = ResourceType.AWESOME_LIST),
            ),
            null,
            "cp",
          )
      }

    RunScout(listOf(c), history, index, null, clock, NO_LOCK).runNow()

    assertEquals(listOf("awesome", "lists"), calls.map { it.first })
    assertEquals(listOf("awesome", "lists"), calls.first().second)
  }

  @Test
  fun `records the source withdrew are forgotten and the run says so`() {
    val c =
      FakeCollector(descriptor("mcp")) {
        CollectedBatch(
          emptyList(),
          null,
          "cp",
          withdrawn = listOf("io.github.x/old", "io.github.x/gone"),
        )
      }
    val withdrawal = RecordingWithdrawal()

    val run =
      RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, withdrawn = withdrawal)
        .runNow()!!
        .single()

    assertEquals(listOf("mcp" to listOf("io.github.x/old", "io.github.x/gone")), withdrawal.calls)
    assertTrue(run.message!!.contains("Removed 2 records the source withdrew"))
  }

  @Test
  fun `a resource that keeps other records goes back to the backlog, its evidence may be gone`() {
    val kept = UUID.randomUUID()
    val requested = mutableListOf<UUID>()
    val c =
      FakeCollector(descriptor("mcp")) {
        CollectedBatch(emptyList(), null, "cp", withdrawn = listOf("io.github.x/old"))
      }

    RunScout(
        listOf(c),
        history,
        catalog,
        null,
        clock,
        NO_LOCK,
        backlog = backlog(requested = requested) { emptyList() },
        withdrawn = RecordingWithdrawal(kept = listOf(kept)),
      )
      .runNow()

    assertEquals(listOf(kept), requested)
  }

  @Test
  fun `nothing withdrawn calls nothing`() {
    val withdrawal = RecordingWithdrawal()
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }

    RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, withdrawn = withdrawal).runNow()

    assertTrue(withdrawal.calls.isEmpty())
  }

  @Test
  fun `a failing withdrawal does not fail the run`() {
    val c =
      FakeCollector(descriptor("mcp")) {
        CollectedBatch(emptyList(), null, "cp", withdrawn = listOf("x"))
      }

    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          null,
          clock,
          NO_LOCK,
          withdrawn = RecordingWithdrawal(failure = IllegalStateException("db down")),
        )
        .runNow()!!
        .single()

    assertEquals(RunStatus.SUCCEEDED, run.status)
  }

  @Test
  fun `a failing withdrawal keeps the cursor so the next run reads the withdrawal again`() {
    history.cursors["mcp"] = ScoutCursor("2026-10-20T00:00:00Z", null, null)
    val c =
      FakeCollector(descriptor("mcp")) {
        CollectedBatch(emptyList(), null, "cp", withdrawn = listOf("io.github.x/gone"))
      }

    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          null,
          clock,
          NO_LOCK,
          withdrawn = RecordingWithdrawal(failure = IllegalStateException("db down")),
        )
        .runNow()!!
        .single()

    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals("2026-10-20T00:00:00Z", history.cursors["mcp"]!!.checkpoint)
  }

  @Test
  fun `checkpoint advances only after a complete pass and is passed next time`() {
    val store = history
    var complete = false
    val c =
      FakeCollector(descriptor("mcp")) {
        CollectedBatch(
          emptyList(),
          if (complete) null else "page2",
          if (complete) "cp-done" else null,
        )
      }
    val runner = RunScout(listOf(c), store, catalog, null, clock, NO_LOCK)
    runner.runNow()
    assertNull(store.cursors["mcp"]!!.checkpoint)
    complete = true
    runner.runNow()
    runner.runNow()
    assertEquals(listOf(null, null, NOW.toString()), c.seenCheckpoints)
  }

  @Test
  fun `an llm outage or a spent budget is recorded once, not fatal, and stops enrichment for the rest of the run`() {
    val saved = mutableListOf<EnrichmentOutcome>()
    val store =
      object : FakeEnrichmentStore() {
        override fun save(
          resourceId: UUID,
          model: EnrichmentModel,
          inputFingerprint: String,
          startedAt: java.time.Instant,
          result: ValidationResult?,
          outcome: EnrichmentOutcome,
          failure: String?,
          extraEvidence: String?,
        ) {
          saved += outcome
        }
      }
    val model =
      object : EnrichmentModel {
        override val provider = "none"
        override val model = "none"
        override val promptVersion = "enrich-v3"

        override fun propose(input: EnrichmentInput): EnrichmentProposal =
          throw LlmUnavailable("daily enrichment budget used up")
      }
    val stranded = mutableListOf<Int>()
    val requested = mutableListOf<UUID>()
    val hits = (0 until 5).map { hit("mcp", "https://github.com/a/r$it") }
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          EnrichResource(model, store, clock),
          clock,
          NO_LOCK,
          maxEnrichmentsPerRun = { 3 },
          backlog =
            backlog(requested = requested) { limit ->
              stranded += limit
              listOf(UUID.randomUUID())
            },
        )
        .runNow()!!
        .single()
    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(0, run.enrichmentsQueued)
    assertEquals(listOf(EnrichmentOutcome.LLM_UNAVAILABLE), saved)
    assertEquals(emptyList(), stranded)
    assertEquals(changedIds.drop(1), requested, "the rest wait for the backlog")
    assertEquals("4 changed resources wait for the backlog (enrichment budget spent)", run.message)
  }

  @Test
  fun `changed resources without a slot left go back to the backlog for a later run`() {
    val store = RecordingEnrichmentStore()
    val requested = mutableListOf<UUID>()
    val hits = (0 until 3).map { hit("mcp", "https://github.com/a/r$it") }
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          EnrichResource(model(true), store, clock),
          clock,
          NO_LOCK,
          maxEnrichmentsPerRun = { 1 },
          backlog = backlog(requested = requested) { emptyList() },
        )
        .runNow()!!
        .single()
    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(1, run.enrichmentsQueued)
    assertEquals(changedIds.take(1), store.enriched)
    assertEquals(changedIds.drop(1), requested)
    assertEquals("2 changed resources wait for the backlog (enrichment budget spent)", run.message)
  }

  @Test
  fun `a changed resource whose source text the last run already judged does not go back to the backlog`() {
    val sameText = mutableSetOf<UUID>()
    val store =
      object : RecordingEnrichmentStore() {
        override fun lastFingerprint(resourceId: UUID) =
          if (resourceId in sameText) material().fingerprintFor(model(true)) else null
      }
    val requested = mutableListOf<UUID>()
    val hits = (0 until 3).map { hit("mcp", "https://github.com/a/r$it") }
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
    val catalog = CatalogIndex { results, _ ->
      results
        .map { it.id.value }
        .also { ids ->
          sameText += ids[1]
          changedIds += ids
        }
    }
    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          EnrichResource(model(true), store, clock),
          clock,
          NO_LOCK,
          maxEnrichmentsPerRun = { 1 },
          backlog = backlog(requested = requested) { emptyList() },
        )
        .runNow()!!
        .single()
    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(changedIds.take(1), store.enriched)
    assertEquals(changedIds.drop(2), requested, "only the resource with new text waits")
    assertEquals("1 changed resource waits for the backlog (enrichment budget spent)", run.message)
  }

  @Test
  fun `a run for an unknown source id is refused before the lock and leaves no record`() {
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }
    var lockAsked = false
    val lock =
      object : RunLock {
        override fun <T : Any> runExclusively(task: Supplier<T>): T? {
          lockAsked = true
          return task.get()
        }
      }
    val runner = RunScout(listOf(c), history, catalog, null, clock, lock)

    val refused = assertFailsWith<UnknownSource> { runner.tryStart("nope") }

    assertEquals("nope", refused.sourceId)
    assertEquals(false, lockAsked)
    assertEquals(emptyMap(), history.runs)
    assertEquals(0, c.seenCheckpoints.size)
  }

  @Test
  fun `a resource whose source text has not changed since its last successful run is not sent again`() {
    var calls = 0
    val saved = mutableListOf<EnrichmentOutcome>()
    val store =
      object : FakeEnrichmentStore() {
        override fun lastFingerprint(resourceId: UUID) = material().fingerprintFor(model(true))

        override fun save(
          resourceId: UUID,
          model: EnrichmentModel,
          inputFingerprint: String,
          startedAt: java.time.Instant,
          result: ValidationResult?,
          outcome: EnrichmentOutcome,
          failure: String?,
          extraEvidence: String?,
        ) {
          saved += outcome
        }
      }
    val model =
      object : EnrichmentModel {
        override val provider = "fake"
        override val model = "m"
        override val promptVersion = "enrich-v3"

        override fun propose(input: EnrichmentInput): EnrichmentProposal =
          EnrichmentProposal().also { calls++ }
      }
    val meters = SimpleMeterRegistry()
    val c =
      FakeCollector(descriptor("mcp")) {
        CollectedBatch(listOf(hit("mcp", "https://github.com/a/r1")), null, "cp")
      }

    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          EnrichResource(model, store, clock, meters = meters),
          clock,
          NO_LOCK,
        )
        .runNow()!!
        .single()

    assertEquals(0, calls)
    assertEquals(emptyList(), saved)
    assertEquals(0, run.enrichmentsQueued)
    assertEquals(
      1.0,
      meters.get("searchavo.scout.enrichment").tag("outcome", "UNCHANGED").counter().count(),
    )
  }

  @Test
  fun `an unchanged resource gives its slot back, so no-op checks do not use up the run's enrichments`() {
    val unchanged = mutableSetOf<UUID>()
    val store =
      object : FakeEnrichmentStore() {
        val enriched = mutableListOf<UUID>()

        override fun lastFingerprint(resourceId: UUID) =
          if (resourceId in unchanged) material().fingerprintFor(model(true)) else null

        override fun save(
          resourceId: UUID,
          model: EnrichmentModel,
          inputFingerprint: String,
          startedAt: java.time.Instant,
          result: ValidationResult?,
          outcome: EnrichmentOutcome,
          failure: String?,
          extraEvidence: String?,
        ) {
          enriched += resourceId
        }
      }
    val hits = (0 until 3).map { hit("mcp", "https://github.com/a/same$it") }
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
    val stranded = (0 until 4).map { UUID.randomUUID() }
    unchanged += stranded[0]
    val catalog = CatalogIndex { results, _ ->
      results
        .map { it.id.value }
        .also { ids ->
          unchanged += ids
          changedIds += ids
        }
    }

    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          EnrichResource(model(true), store, clock),
          clock,
          NO_LOCK,
          maxEnrichmentsPerRun = { 2 },
          backlog = backlog { limit -> stranded.take(limit) },
        )
        .runNow()!!
        .single()

    assertEquals(0, run.enrichmentsQueued)
    assertEquals(stranded.subList(1, 3), store.enriched)
  }

  @Test
  fun `relationship targets are resolved against the catalog only for the names the model proposed`() {
    val target = UUID.randomUUID()
    val asked = mutableListOf<Collection<String>>()
    var checked: ValidationResult? = null
    val evidence = UUID.randomUUID()
    val store =
      object : FakeEnrichmentStore() {
        override fun load(resourceId: UUID) =
          EnrichmentMaterial(
            EnrichmentInput(
              "pg-mcp",
              listOf(
                Material(
                  evidence,
                  URI("https://x.example"),
                  NOW,
                  "Postgres MCP server. Works with Claude Desktop.",
                )
              ),
            ),
            "fp",
          )

        override fun resourcesTitled(titles: Collection<String>, resourceId: UUID) =
          mapOf("claude desktop" to target).also { asked += titles }

        override fun save(
          resourceId: UUID,
          model: EnrichmentModel,
          inputFingerprint: String,
          startedAt: java.time.Instant,
          result: ValidationResult?,
          outcome: EnrichmentOutcome,
          failure: String?,
          extraEvidence: String?,
        ) {
          checked = result
        }
      }
    val model =
      object : EnrichmentModel {
        override val provider = "fake"
        override val model = "m"
        override val promptVersion = "enrich-v3"

        override fun propose(input: EnrichmentInput) =
          EnrichmentProposal(
            relationships =
              listOf(
                ProposedRelationship(
                  "COMPATIBLE_WITH",
                  " Claude Desktop ",
                  listOf(Citation(evidence.toString(), "Works with Claude Desktop")),
                )
              )
          )
      }

    EnrichResource(model, store, clock).enrich(UUID.randomUUID())

    assertEquals(listOf<Collection<String>>(listOf("Claude Desktop")), asked)
    assertEquals(target, checked!!.relationships.single().targetId)
  }

  @Test
  fun `disabled collector is skipped and recorded`() {
    val off = FakeCollector(descriptor("off", enabled = false)) { error("must not run") }
    assertEquals(
      RunStatus.SKIPPED,
      RunScout(listOf(off), history, catalog, null, clock, NO_LOCK).runNow()!!.single().status,
    )
    assertEquals(0, off.seenCheckpoints.size)
  }

  private open class RecordingEnrichmentStore : FakeEnrichmentStore() {
    val enriched = mutableListOf<UUID>()

    override fun save(
      resourceId: UUID,
      model: EnrichmentModel,
      inputFingerprint: String,
      startedAt: java.time.Instant,
      result: ValidationResult?,
      outcome: EnrichmentOutcome,
      failure: String?,
      extraEvidence: String?,
    ) {
      enriched += resourceId
    }
  }

  private fun backlog(
    eligible: (List<UUID>) -> List<UUID> = { it },
    requested: MutableList<UUID> = mutableListOf(),
    pending: (Int) -> List<UUID>,
  ) =
    object : EnrichmentBacklog {
      override fun pending(limit: Int) = pending(limit)

      override fun eligible(resourceIds: List<UUID>) = eligible(resourceIds)

      override fun waiting(resourceIds: List<UUID>) = resourceIds

      override fun requestRun(resourceId: UUID) {
        requested += resourceId
      }

      override fun pendingCount() = 0L

      override fun queue(
        view: ai.searchavo.catalog.enrichment.EnrichmentCandidates.View,
        after: ai.searchavo.catalog.enrichment.EnrichmentCandidates.BacklogCursor?,
        size: Int,
      ) = emptyList<ai.searchavo.catalog.enrichment.EnrichmentCandidates.BacklogEntry>()

      override fun place(resourceId: UUID) = null

      override fun prioritisedCount() = 0
    }

  private fun model(available: Boolean) =
    object : EnrichmentModel {
      override val provider = if (available) "fake" else "none"
      override val model = "m"
      override val promptVersion = "enrich-v3"
      override val available = available

      override fun propose(input: EnrichmentInput): EnrichmentProposal =
        if (available) EnrichmentProposal(fields = listOf(STATED))
        else throw LlmUnavailable("no model")
    }

  @Test
  fun `budget left after changed resources goes to the backlog, without enriching anything twice`() {
    val store = RecordingEnrichmentStore()
    val hits = (0 until 2).map { hit("mcp", "https://github.com/a/r$it") }
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
    val stranded = (0 until 5).map { UUID.randomUUID() }
    val requestedLimits = mutableListOf<Int>()
    val backlog = backlog { limit ->
      requestedLimits += limit
      (changedIds.take(1) + stranded).take(limit)
    }

    RunScout(
        listOf(c),
        history,
        catalog,
        EnrichResource(model(true), store, clock),
        clock,
        NO_LOCK,
        maxEnrichmentsPerRun = { 4 },
        backlog = backlog,
      )
      .runNow()!!

    assertEquals(changedIds + stranded.take(2), store.enriched)
    assertEquals(listOf(4), requestedLimits)
  }

  @Test
  fun `changed resources without enough text are not sent to the model`() {
    val store = RecordingEnrichmentStore()
    val hits = (0 until 4).map { hit("hf", "https://huggingface.co/m/r$it") }
    val c = FakeCollector(descriptor("hf")) { CollectedBatch(hits, null, "cp") }
    val withText = mutableListOf<UUID>()
    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          EnrichResource(model(true), store, clock),
          clock,
          NO_LOCK,
          backlog =
            backlog(eligible = { ids -> ids.take(1).also { withText += it } }) { emptyList() },
        )
        .runNow()!!
        .single()
    assertEquals(1, run.enrichmentsQueued)
    assertEquals(withText, store.enriched)
  }

  @Test
  fun `the technical reason of a failed or unavailable call is saved`() {
    val reasons = mutableListOf<Pair<EnrichmentOutcome, String?>>()
    val store =
      object : FakeEnrichmentStore() {
        override fun save(
          resourceId: UUID,
          model: EnrichmentModel,
          inputFingerprint: String,
          startedAt: java.time.Instant,
          result: ValidationResult?,
          outcome: EnrichmentOutcome,
          failure: String?,
          extraEvidence: String?,
        ) {
          reasons += outcome to failure
        }
      }
    fun throwing(e: RuntimeException) =
      object : EnrichmentModel {
        override val provider = "fake"
        override val model = "m"
        override val promptVersion = "enrich-v3"

        override fun propose(input: EnrichmentInput): EnrichmentProposal = throw e
      }
    EnrichResource(throwing(LlmUnavailable("provider unavailable: 429")), store, clock)
      .enrich(UUID.randomUUID())
    EnrichResource(throwing(ModelAnswerInvalid("answer contains no JSON object")), store, clock)
      .enrich(UUID.randomUUID())
    assertEquals<List<Pair<EnrichmentOutcome, String?>>>(
      listOf(
        EnrichmentOutcome.LLM_UNAVAILABLE to "LlmUnavailable: provider unavailable: 429",
        EnrichmentOutcome.FAILED to "ModelAnswerInvalid: answer contains no JSON object",
      ),
      reasons,
    )
  }

  @Test
  fun `one budget is shared by all collectors in a run`() {
    val store = RecordingEnrichmentStore()
    val a =
      FakeCollector(descriptor("a")) {
        CollectedBatch((0 until 3).map { hit("a", "https://github.com/a/a$it") }, null, "cp")
      }
    val b =
      FakeCollector(descriptor("b")) {
        CollectedBatch((0 until 3).map { hit("b", "https://github.com/b/b$it") }, null, "cp")
      }
    val runs =
      RunScout(
          listOf(a, b),
          history,
          catalog,
          EnrichResource(model(true), store, clock),
          clock,
          NO_LOCK,
          maxEnrichmentsPerRun = { 4 },
        )
        .runNow()!!
    assertEquals(listOf(3, 1), runs.map { it.enrichmentsQueued })
    assertEquals(4, store.enriched.size)
  }

  @Test
  fun `backlog is left alone while no model is configured`() {
    val store = RecordingEnrichmentStore()
    var asked = false
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }
    RunScout(
        listOf(c),
        history,
        catalog,
        EnrichResource(model(false), store, clock),
        clock,
        NO_LOCK,
        backlog =
          backlog {
            asked = true
            listOf(UUID.randomUUID())
          },
      )
      .runNow()!!
    assertEquals(false, asked)
    assertEquals(emptyList(), store.enriched)
  }

  @Test
  fun `a failing backlog query does not fail the collector runs`() {
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }
    val runs =
      RunScout(
          listOf(c),
          history,
          catalog,
          EnrichResource(model(true), RecordingEnrichmentStore(), clock),
          clock,
          NO_LOCK,
          backlog = backlog { error("db down") },
        )
        .runNow()!!
    assertEquals(listOf(RunStatus.SUCCEEDED), runs.map { it.status })
  }

  @Test
  fun `an enrichment error after indexing does not fail the run or stop the other enrichments`() {
    val hits = (0 until 3).map { hit("mcp", "https://github.com/a/r$it") }
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
    val enriched = mutableListOf<UUID>()
    val store =
      object : FakeEnrichmentStore() {
        override fun load(resourceId: UUID): EnrichmentMaterial {
          if (resourceId == changedIds.first()) throw IllegalStateException("connection reset")
          return material()
        }

        override fun save(
          resourceId: UUID,
          model: EnrichmentModel,
          inputFingerprint: String,
          startedAt: java.time.Instant,
          result: ValidationResult?,
          outcome: EnrichmentOutcome,
          failure: String?,
          extraEvidence: String?,
        ) {
          enriched += resourceId
        }
      }
    val scoutStore = history
    val requested = mutableListOf<UUID>()

    val run =
      RunScout(
          listOf(c),
          scoutStore,
          catalog,
          EnrichResource(model(true), store, clock),
          clock,
          NO_LOCK,
          backlog = backlog(requested = requested) { emptyList() },
        )
        .runNow()!!
        .single()

    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(3, run.itemsSeen)
    assertEquals(2, run.enrichmentsQueued)
    assertEquals(changedIds.drop(1), enriched)
    assertEquals(changedIds.take(1), requested, "the failed one goes back to the backlog")
    assertEquals("1 enrichment failed; the backlog retries it", run.message)
    assertEquals(ScoutCursor(NOW.toString(), null, null), scoutStore.cursors["mcp"])
  }

  @Test
  fun `a failing backlog enrichment does not stop the rest of the backlog`() {
    val stranded = (0 until 3).map { UUID.randomUUID() }
    val enriched = mutableListOf<UUID>()
    val store =
      object : FakeEnrichmentStore() {
        override fun load(resourceId: UUID): EnrichmentMaterial {
          if (resourceId == stranded[0]) throw IllegalStateException("connection reset")
          return material()
        }

        override fun save(
          resourceId: UUID,
          model: EnrichmentModel,
          inputFingerprint: String,
          startedAt: java.time.Instant,
          result: ValidationResult?,
          outcome: EnrichmentOutcome,
          failure: String?,
          extraEvidence: String?,
        ) {
          enriched += resourceId
        }
      }
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }
    RunScout(
        listOf(c),
        history,
        catalog,
        EnrichResource(model(true), store, clock),
        clock,
        NO_LOCK,
        backlog = backlog { limit -> stranded.take(limit) },
      )
      .runNow()!!
    assertEquals(stranded.drop(1), enriched)
  }

  @Test
  fun `a failing run-history store does not stop the other collectors`() {
    val store =
      object : ScoutStore by MemStore() {
        override fun save(run: RunSummary) = throw IllegalStateException("db down")
      }
    val a =
      FakeCollector(descriptor("a")) {
        CollectedBatch(listOf(hit("a", "https://github.com/a/x")), null, "cp")
      }
    val off = FakeCollector(descriptor("off", enabled = false)) { error("must not run") }
    val b =
      FakeCollector(descriptor("b")) {
        CollectedBatch(listOf(hit("b", "https://github.com/b/y")), null, "cp")
      }

    val started = RunScout(listOf(a, off, b), store, catalog, null, clock, NO_LOCK).tryStart()!!

    assertEquals(
      listOf(RunStatus.RUNNING, RunStatus.SKIPPED, RunStatus.RUNNING),
      started.map { it.status },
    )
    assertEquals(listOf(1, 1), listOf(a, b).map { it.seenCheckpoints.size })
  }

  @Test
  fun `a collector that is not due is recorded as skipped and keeps its checkpoint`() {
    val store = history
    store.cursors["live-web"] = ScoutCursor("2026-10-20T00:00:00Z", null, null)
    val c = FakeCollector(descriptor("live-web")) { CollectedBatch.notDue() }
    val run = RunScout(listOf(c), store, catalog, null, clock, NO_LOCK).runNow()!!.single()
    assertEquals(RunStatus.SKIPPED, run.status)
    assertEquals("2026-10-20T00:00:00Z", store.cursors["live-web"]!!.checkpoint)
  }

  @Test
  fun `a pass that fails or is deferred stays open, and the next run gets it`() {
    val failing = FakeCollector(descriptor("failing")) { throw IllegalStateException("boom") }
    val capped = FakeCollector(descriptor("capped")) { CollectedBatch.deferred("cap reached") }
    val idle = FakeCollector(descriptor("idle")) { CollectedBatch.notDue() }
    val runs =
      RunScout(listOf(failing, capped, idle), history, catalog, null, clock, NO_LOCK).runNow()!!

    assertEquals(
      listOf(RunStatus.FAILED, RunStatus.SKIPPED, RunStatus.SKIPPED),
      runs.map { it.status },
    )
    assertEquals("cap reached", runs[1].message)
    assertEquals(ScoutCursor(null, null, NOW.toString()), history.cursors["failing"])
    assertEquals(ScoutCursor(null, null, NOW.toString()), history.cursors["capped"])
    assertNull(history.cursors["idle"])

    val seen = mutableListOf<ScoutCursor>()
    val retry =
      object : Collector {
        override fun runEvery(): Duration = Duration.ZERO

        override val descriptor = descriptor("failing")

        override fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int) =
          error("the Scout passes the whole cursor")

        override fun collect(cursor: ScoutCursor, maxItems: Int): CollectedBatch {
          seen += cursor
          return CollectedBatch(emptyList(), null, "done")
        }
      }
    RunScout(listOf(retry), history, catalog, null, clock, NO_LOCK).runNow()

    assertEquals(listOf(ScoutCursor(null, null, NOW.toString())), seen)
    assertEquals(ScoutCursor(NOW.toString(), null, null), history.cursors["failing"])
  }

  @Test
  fun `unfinished pass resumes from the saved cursor and checkpoints at the pass start`() {
    val first = java.time.Instant.parse("2026-10-26T10:00:00Z")
    val second = java.time.Instant.parse("2026-10-26T11:00:00Z")
    val movingClock = MovableClock(first)
    val seen = mutableListOf<Pair<String?, String?>>()
    val collector =
      object : Collector {
        override fun runEvery(): Duration = Duration.ZERO

        override val descriptor = descriptor("mcp")

        override fun collect(
          checkpoint: String?,
          resumeCursor: String?,
          maxItems: Int,
        ): CollectedBatch {
          seen += checkpoint to resumeCursor
          return if (resumeCursor == null) CollectedBatch(emptyList(), "p2", null)
          else CollectedBatch(emptyList(), null, "done")
        }
      }
    val store = history
    val runner = RunScout(listOf(collector), store, catalog, null, movingClock, NO_LOCK)

    runner.runNow()
    assertEquals(ScoutCursor(null, "p2", first.toString()), store.cursors["mcp"])
    movingClock.now = second
    runner.runNow()

    assertEquals(listOf<Pair<String?, String?>>(null to null, null to "p2"), seen)
    assertEquals(ScoutCursor(first.toString(), null, null), store.cursors["mcp"])
  }

  @Test
  fun `while another run holds the lock nothing is collected and the start is refused`() {
    var collected = 0
    val c =
      FakeCollector(descriptor("mcp")) {
        collected++
        CollectedBatch(emptyList(), null, "cp1")
      }
    val store = history
    val held =
      object : RunLock {
        override fun <T : Any> runExclusively(task: Supplier<T>): T? = null
      }

    assertNull(RunScout(listOf(c), store, catalog, null, clock, held).tryStart())
    assertEquals(0, collected)
    assertEquals(emptyList(), store.recent(10))
  }

  @Test
  fun `a started run answers with its planned runs at once and collects in the background`() {
    val release = CountDownLatch(1)
    val slow =
      FakeCollector(descriptor("slow")) {
        release.await(10, TimeUnit.SECONDS)
        CollectedBatch(emptyList(), null, "cp")
      }
    val off = FakeCollector(descriptor("off", enabled = false)) { error("must not run") }
    val store = history
    Executors.newVirtualThreadPerTaskExecutor().use { threads ->
      val started =
        RunScout(listOf(slow, off), store, catalog, null, clock, NO_LOCK, launcher = threads)
          .tryStart()!!

      assertEquals(listOf(RunStatus.RUNNING, RunStatus.SKIPPED), started.map { it.status })
      assertEquals(started, store.recent(10))
      release.countDown()
      while (store.runs.getValue(started[0].id).status == RunStatus.RUNNING) Thread.sleep(10)
      assertEquals(
        listOf(RunStatus.SUCCEEDED, RunStatus.SKIPPED),
        started.map { store.runs.getValue(it.id).status },
      )
    }
  }

  @Test
  fun `runs left RUNNING by a stopped replica are closed as failed before the next run`() {
    val store = history
    val stale =
      RunSummary(
        UUID.randomUUID(),
        "mcp",
        RunStatus.RUNNING,
        NOW.minusSeconds(3600),
        null,
        0,
        0,
        0,
        null,
      )
    store.save(stale)
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }

    RunScout(listOf(c), store, catalog, null, clock, NO_LOCK).runNow()

    val closed = store.runs.getValue(stale.id)
    assertEquals(RunStatus.FAILED, closed.status)
    assertEquals(NOW, closed.finishedAt)
    assertEquals(listOf(RunStatus.FAILED, RunStatus.SUCCEEDED), store.runs.values.map { it.status })
  }

  @Test
  fun `interrupted runs are closed only while no run holds the lock`() {
    val store = history
    store.save(RunSummary(UUID.randomUUID(), "mcp", RunStatus.RUNNING, NOW, null, 0, 0, 0, null))
    val held =
      object : RunLock {
        override fun <T : Any> runExclusively(task: Supplier<T>): T? = null
      }

    assertEquals(
      false,
      RunScout(emptyList(), store, catalog, null, clock, held).closeInterruptedRuns(),
    )
    assertEquals(RunStatus.RUNNING, store.runs.values.single().status)

    assertEquals(
      true,
      RunScout(emptyList(), store, catalog, null, clock, NO_LOCK).closeInterruptedRuns(),
    )
    assertEquals(RunStatus.FAILED, store.runs.values.single().status)
  }

  private fun candidate(url: String) =
    Candidate(
      CanonicalUrl.of(url)!!,
      "AI agents course",
      "A course on agents.",
      "Hugging Face Learn",
      ResourceType.COURSE,
    )

  @Test
  fun `candidates a collector returns are proposed for review under its source id and never indexed`() {
    val proposed = mutableListOf<Pair<String, List<Candidate>>>()
    val review = CandidateReview { sourceId, candidates ->
      proposed += sourceId to candidates
      Proposal(candidates.size)
    }
    val found = listOf(candidate("https://huggingface.co/learn/agents-course"))
    val c =
      FakeCollector(descriptor("course-scout")) {
        CollectedBatch(emptyList(), null, "cp", candidates = found)
      }

    val run =
      RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review)
        .runNow()!!
        .single()

    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(listOf("course-scout" to found), proposed)
    assertEquals(emptyList(), changedIds)
    assertEquals(NOW.toString(), history.cursors["course-scout"]!!.checkpoint)
  }

  private fun repository(url: String, signals: CandidateSignals) =
    Candidate(
      CanonicalUrl.of(url)!!,
      url.removePrefix("https://github.com/"),
      "An agent library.",
      null,
      ResourceType.LIBRARY,
      signals = signals,
    )

  @Test
  fun `an archived candidate is not proposed`() {
    val meters = SimpleMeterRegistry()
    val proposed = mutableListOf<Candidate>()
    val review = CandidateReview { _, candidates ->
      proposed += candidates
      Proposal(candidates.size)
    }
    val archived =
      repository("https://github.com/a/archived", CandidateSignals(stars = 900, archived = true))
    val active =
      repository("https://github.com/a/active", CandidateSignals(stars = 900, archived = false))
    val unknown = repository("https://github.com/a/unknown", CandidateSignals.UNKNOWN)
    val c =
      FakeCollector(descriptor("github-sdks")) {
        CollectedBatch(emptyList(), null, "cp", candidates = listOf(archived, active, unknown))
      }

    val run =
      RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review, meters = meters)
        .runNow()!!
        .single()

    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(listOf(active, unknown), proposed)
    assertEquals(
      1.0,
      meters
        .get("searchavo.source.items.dropped")
        .tags("source", "github-sdks", "reason", "archived")
        .counter()
        .count(),
    )
  }

  @Test
  fun `an idle fork is not proposed, an active fork is`() {
    val proposed = mutableListOf<Candidate>()
    val review = CandidateReview { _, candidates ->
      proposed += candidates
      Proposal(candidates.size)
    }
    val longAgo = NOW.minus(java.time.Duration.ofDays(2000))
    val idle =
      repository(
        "https://github.com/a/idle-fork",
        CandidateSignals(stars = 2, lastPush = longAgo, fork = true),
      )
    val alive =
      repository(
        "https://github.com/a/active-fork",
        CandidateSignals(stars = 2, lastPush = NOW.minusSeconds(60), fork = true),
      )
    val c =
      FakeCollector(descriptor("github-sdks")) {
        CollectedBatch(emptyList(), null, "cp", candidates = listOf(idle, alive))
      }

    RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review).runNow()!!

    assertEquals(listOf(alive), proposed)
  }

  @Test
  fun `a batch whose candidates are all refused opens no review`() {
    val review = CandidateReview { _, _ -> error("must not be called") }
    val archived = repository("https://github.com/a/archived", CandidateSignals(archived = true))
    val c =
      FakeCollector(descriptor("github-sdks")) {
        CollectedBatch(emptyList(), null, "cp", candidates = listOf(archived))
      }

    val run =
      RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review)
        .runNow()!!
        .single()

    assertEquals(RunStatus.SUCCEEDED, run.status)
  }

  private fun listText(url: String) =
    ListText(
      CanonicalUrl.of(url)!!,
      "Tools\n",
      listOf(ListEntryText(CanonicalUrl.of("https://github.com/a/b")!!, "B - a tool", "Tools")),
    )

  @Test
  fun `list text a collector returns reaches the list index after its hits are indexed`() {
    val order = mutableListOf<String>()
    val indexed = mutableListOf<ListText>()
    val lists = ListIndex { texts ->
      order += "lists"
      indexed += texts
    }
    val texts = listOf(listText("https://github.com/acme/awesome-llm"))
    val c =
      FakeCollector(descriptor("github-awesome")) {
        CollectedBatch(
          listOf(hit("github-awesome", "https://github.com/a/b")),
          null,
          "cp",
          lists = texts,
        )
      }
    val indexingCatalog = CatalogIndex { results, source ->
      order += "hits"
      catalog.index(results, source)
    }

    val run =
      RunScout(listOf(c), history, indexingCatalog, null, clock, NO_LOCK, lists = lists)
        .runNow()!!
        .single()

    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(texts, indexed)
    assertEquals(listOf("hits", "lists"), order)
  }

  @Test
  fun `a list index that fails does not fail the pass`() {
    val lists = ListIndex { throw IllegalStateException("database down") }
    val c =
      FakeCollector(descriptor("github-awesome")) {
        CollectedBatch(
          emptyList(),
          null,
          "cp",
          lists = listOf(listText("https://github.com/acme/awesome-llm")),
        )
      }

    val run =
      RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, lists = lists).runNow()!!.single()

    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(NOW.toString(), history.cursors["github-awesome"]!!.checkpoint)
  }

  @Test
  fun `a batch without candidates opens no review`() {
    val review = CandidateReview { _, _ -> error("must not be called") }
    val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }
    assertEquals(
      RunStatus.SUCCEEDED,
      RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review)
        .runNow()!!
        .single()
        .status,
    )
  }

  @Test
  fun `a review list that fails fails the run and keeps the checkpoint, so the next pass proposes again`() {
    history.cursors["course-scout"] = ScoutCursor("2026-10-20T00:00:00Z", null, null)
    val review = CandidateReview { _, _ -> throw IllegalStateException("database down") }
    val c =
      FakeCollector(descriptor("course-scout")) {
        CollectedBatch(
          emptyList(),
          null,
          "cp",
          candidates = listOf(candidate("https://www.coursera.org/learn/ai")),
        )
      }

    val run =
      RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review)
        .runNow()!!
        .single()

    assertEquals(RunStatus.FAILED, run.status)
    assertEquals("2026-10-20T00:00:00Z", history.cursors["course-scout"]!!.checkpoint)
  }

  @Test
  fun `a pass whose new candidates the daily cap held back stays open, and closes at its start once none are left`() {
    history.cursors["leaders"] = ScoutCursor("2026-10-20T00:00:00Z", null, null)
    var heldBack = 3
    val review = CandidateReview { _, candidates -> Proposal(candidates.size, heldBack) }
    val seen = mutableListOf<String?>()
    val c =
      object : Collector {
        override fun runEvery(): Duration = Duration.ZERO

        override val descriptor = descriptor("leaders")

        override fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int) =
          CollectedBatch(
              emptyList(),
              null,
              "cp",
              candidates = listOf(candidate("https://github.com/a/b")),
            )
            .also { seen += resumeCursor }
      }
    val scout = RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review)

    scout.runNow()
    val open = history.cursors["leaders"]!!
    heldBack = 0
    scout.runNow()

    assertEquals("2026-10-20T00:00:00Z", open.checkpoint)
    assertTrue(open.candidatesHeldBack)
    assertNull(open.ownResume)
    assertEquals(listOf(null, open.resumeCursor), seen)
    assertEquals(ScoutCursor(open.passStartedAt, null, null), history.cursors["leaders"])
  }

  private fun leader(name: String, stars: Long, pushed: Instant) =
    SourceHit(
      sourceId = AdmissionFixtures.TOPIC_LEADERS,
      externalId = name,
      url = URI("https://github.com/$name"),
      canonicalUrl = CanonicalUrl.of("https://github.com/$name")!!,
      title = name,
      type = ResourceType.LIBRARY,
      updated = DateFact.fromSource(pushed, "github", NOW),
      popularity = listOf(PopularityMetric(PopularityKind.GITHUB_STARS, stars, "github", NOW)),
      retrievedAt = NOW,
    )

  @Test
  fun `a hit under the floor of its collector is not indexed, and no drop is counted for it`() {
    val meters = SimpleMeterRegistry()
    val indexed = mutableListOf<SourceHit>()
    val catalog = CatalogIndex { results, _ ->
      indexed += results.flatMap { it.hits }
      emptyList()
    }
    val c =
      FakeCollector(descriptor(AdmissionFixtures.TOPIC_LEADERS)) {
        CollectedBatch(
          listOf(
            leader("few/stars", 999, NOW),
            leader("pytorch/pytorch", 90_000, NOW),
            leader("long/quiet", 5_000, NOW.minus(java.time.Duration.ofDays(91))),
          ),
          null,
          "cp",
        )
      }

    val run =
      RunScout(
          listOf(c),
          history,
          catalog,
          null,
          clock,
          NO_LOCK,
          meters = meters,
          admission = AdmissionFixtures.AS_CONFIGURED,
        )
        .runNow()!!
        .single()

    assertEquals(RunStatus.SUCCEEDED, run.status)
    assertEquals(listOf("pytorch/pytorch"), indexed.map { it.externalId })
    assertEquals(listOf(0), indexed.map { it.rank })
    assertEquals(3, run.itemsSeen)
    assertTrue(meters.find("searchavo.source.items.dropped").counters().isEmpty())
  }

  @Test
  fun `a candidate under the index floor of its collector is still proposed, a person decides`() {
    val proposed = mutableListOf<Candidate>()
    val review = CandidateReview { _, candidates ->
      proposed += candidates
      Proposal(candidates.size)
    }
    val small =
      repository("https://github.com/a/small", CandidateSignals(stars = 12, archived = false))
    val c =
      FakeCollector(descriptor(AdmissionFixtures.TOPIC_LEADERS)) {
        CollectedBatch(emptyList(), null, "cp", candidates = listOf(small))
      }

    RunScout(
        listOf(c),
        history,
        catalog,
        null,
        clock,
        NO_LOCK,
        review = review,
        admission = AdmissionFixtures.AS_CONFIGURED,
      )
      .runNow()

    assertEquals(listOf(small), proposed)
  }

  @Test
  fun `a cursor the collector wrote is its own resume, and holds no candidates back`() {
    val own = ScoutCursor("cp", "page-3", "start")

    assertEquals("page-3", own.ownResume)
    assertTrue(!own.candidatesHeldBack)
    assertNull(ScoutCursor(null, null, null).ownResume)
  }
}
