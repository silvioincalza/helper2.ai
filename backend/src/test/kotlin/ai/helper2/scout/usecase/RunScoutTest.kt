package ai.helper2.scout.usecase

import ai.helper2.scout.Candidate
import ai.helper2.scout.CandidateReview
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.Citation
import ai.helper2.scout.CollectedBatch
import ai.helper2.scout.Collector
import ai.helper2.scout.EnrichmentBacklog
import ai.helper2.scout.EnrichmentInput
import ai.helper2.scout.EnrichmentMaterial
import ai.helper2.scout.EnrichmentModel
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.EnrichmentProposal
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.LlmUnavailable
import ai.helper2.scout.Material
import ai.helper2.scout.ModelAnswerInvalid
import ai.helper2.scout.ProposedRelationship
import ai.helper2.scout.RunLock
import ai.helper2.scout.RunStatus
import ai.helper2.scout.RunSummary
import ai.helper2.scout.ScoutCursor
import ai.helper2.scout.ScoutStore
import ai.helper2.scout.ValidationResult
import ai.helper2.search.NOW
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceFailure
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.ResourceType
import ai.helper2.testing.MovableClock
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.slf4j.LoggerFactory

/** One test runner per test: nothing to exclude. The shared lock is tested in ScoutRunLockIT. */
private val NO_LOCK = object : RunLock {
    override fun <T : Any> runExclusively(task: Supplier<T>): T? = task.get()
}

private fun material() = EnrichmentMaterial(EnrichmentInput("x", listOf(Material(UUID.randomUUID(), URI("https://x.example"), NOW, "text"))), "fp")

class RunScoutTest {

    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val history = MemStore()

    /** Starts a run on the caller's thread (the default launcher), so it has ended on return: its records as saved. */
    private fun RunScout.runNow(): List<RunSummary>? = tryStart()?.map { history.runs.getValue(it.id) }

    private class MemStore : ScoutStore {
        val cursors = mutableMapOf<String, ScoutCursor>()
        val runs = linkedMapOf<UUID, RunSummary>()
        override fun cursor(sourceId: String) = cursors[sourceId]
        override fun saveCursor(sourceId: String, cursor: ScoutCursor) { cursors[sourceId] = cursor }
        override fun save(run: RunSummary) { runs[run.id] = run }
        override fun recent(limit: Int) = runs.values.toList().takeLast(limit)
        override fun markInterrupted(at: Instant): Int {
            val open = runs.values.filter { it.status == RunStatus.RUNNING }
            open.forEach { runs[it.id] = it.copy(status = RunStatus.FAILED, finishedAt = at, message = "Interrupted") }
            return open.size
        }
    }

    private class FakeCollector(override val descriptor: SourceDescriptor, val behaviour: (String?) -> CollectedBatch) : Collector {
        val seenCheckpoints = mutableListOf<String?>()
        override fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int): CollectedBatch {
            seenCheckpoints += checkpoint
            return behaviour(checkpoint)
        }
    }

    /** Material with some text, never enriched before, no known relationship targets; nothing is stored. */
    private open class FakeEnrichmentStore : EnrichmentStore {
        override fun load(resourceId: UUID): EnrichmentMaterial? = material()
        override fun lastFingerprint(resourceId: UUID): String? = null
        override fun resourcesTitled(titles: Collection<String>, resourceId: UUID): Map<String, UUID> = emptyMap()
        override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant,
                          result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {}
    }

    private val changedIds = mutableListOf<UUID>()
    private val catalog = CatalogIndex { results, _ -> results.map { it.id.value }.also { changedIds += it } }

    @Test
    fun `one failing collector does not stop the others`() {
        val bad = FakeCollector(descriptor("bad")) { throw IllegalStateException("boom") }
        val good = FakeCollector(descriptor("good")) { CollectedBatch(listOf(hit("good", "https://github.com/a/x", type = ResourceType.MCP_SERVER)), null, "cp1") }
        val runs = RunScout(listOf(bad, good), history, catalog, null, clock, NO_LOCK).runNow()!!
        assertEquals(listOf(RunStatus.FAILED, RunStatus.SUCCEEDED), runs.map { it.status })
        assertEquals(1, runs[1].itemsSeen)
    }

    @Test
    fun `a failed run logs the failure's message, not only its class`() {
        val logger = LoggerFactory.getLogger(RunScout::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            val c = FakeCollector(descriptor("mcp")) { throw SourceFailure.Unavailable("network error or timeout") }
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
        val good = FakeCollector(descriptor("good")) { CollectedBatch(listOf(hit("good", "https://github.com/a/x")), null, "cp") }
        RunScout(listOf(bad, good), history, catalog, null, clock, NO_LOCK, meters = meters).runNow()!!
        fun count(source: String, status: String, error: String) =
            meters.get("helper2.scout.run").tags("source", source, "status", status, "error", error).timer().count()
        assertEquals(1, count("bad", "failed", "IllegalStateException"))
        assertEquals(1, count("good", "succeeded", "none"))
    }

    @Test
    fun `failed, succeeded and skipped runs all reach the Prometheus scrape`() {
        // Prometheus keeps one tag-key set per meter name and drops the others; SimpleMeterRegistry would not show it.
        val meters = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val bad = FakeCollector(descriptor("bad")) { throw IllegalStateException("boom") }
        val good = FakeCollector(descriptor("good")) { CollectedBatch(listOf(hit("good", "https://github.com/a/x")), null, "cp") }
        val idle = FakeCollector(descriptor("idle")) { CollectedBatch.notDue() }
        RunScout(listOf(bad, good, idle), history, catalog, null, clock, NO_LOCK, meters = meters).runNow()!!
        val runs = meters.scrape().lines().filter { it.startsWith("helper2_scout_run_seconds_count{") }
        fun has(vararg tags: String) = runs.any { line -> tags.all { line.contains(it) } }
        assertEquals(3, runs.size, runs.joinToString("\n"))
        assert(has("source=\"bad\"", "status=\"failed\"", "error=\"IllegalStateException\""))
        assert(has("source=\"good\"", "status=\"succeeded\"", "error=\"none\""))
        assert(has("source=\"idle\"", "status=\"skipped\"", "error=\"none\""))
        assert(meters.scrape().contains("helper2_scout_cycle_seconds_count"))
    }

    @Test
    fun `items counters and last success gauge follow succeeded runs only`() {
        val meters = SimpleMeterRegistry()
        val bad = FakeCollector(descriptor("bad")) { throw IllegalStateException("boom") }
        val hits = (0 until 2).map { hit("good", "https://github.com/a/r$it") }
        val good = FakeCollector(descriptor("good")) { CollectedBatch(hits, null, "cp") }
        RunScout(listOf(bad, good), history, catalog, null, clock, NO_LOCK, meters = meters).runNow()!!
        fun items(kind: String) = meters.get("helper2.scout.items").tags("source", "good", "kind", kind).counter().count()
        assertEquals(2.0, items("seen"))
        assertEquals(2.0, items("changed"))
        assertEquals(0.0, items("queued"))
        assertEquals(NOW.epochSecond.toDouble(), meters.get("helper2.scout.last.success").tag("source", "good").gauge().value())
        assertNull(meters.find("helper2.scout.last.success").tag("source", "bad").gauge())
    }

    @Test
    fun `every enrichment attempt is counted by outcome`() {
        val meters = SimpleMeterRegistry()
        val store = object : FakeEnrichmentStore() {
            override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant, result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {}
        }
        val model = object : EnrichmentModel {
            override val provider = "none"
            override val model = "none"
            override val promptVersion = "enrich-v3"
            override fun propose(input: EnrichmentInput): EnrichmentProposal = throw LlmUnavailable("no model")
        }
        val service = EnrichResource(model, store, clock, meters = meters)
        service.enrich(UUID.randomUUID())
        service.enrich(UUID.randomUUID())
        assertEquals(2.0, meters.get("helper2.scout.enrichment").tag("outcome", "LLM_UNAVAILABLE").counter().count())
    }

    @Test
    fun `checkpoint advances only after a complete pass and is passed next time`() {
        val store = history
        var complete = false
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), if (complete) null else "page2", if (complete) "cp-done" else null) }
        val runner = RunScout(listOf(c), store, catalog, null, clock, NO_LOCK)
        runner.runNow()
        assertNull(store.cursors["mcp"]!!.checkpoint)
        complete = true
        runner.runNow()
        runner.runNow()
        // Checkpoint = start of the pass (NOW, fixed clock), not the collector's own completion time.
        assertEquals(listOf(null, null, NOW.toString()), c.seenCheckpoints)
    }

    @Test
    fun `an llm outage or a spent budget is recorded once, not fatal, and stops enrichment for the rest of the run`() {
        val saved = mutableListOf<EnrichmentOutcome>()
        val store = object : FakeEnrichmentStore() {
            override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant, result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {
                saved += outcome
            }
        }
        val model = object : EnrichmentModel {
            override val provider = "none"
            override val model = "none"
            override val promptVersion = "enrich-v3"
            override fun propose(input: EnrichmentInput): EnrichmentProposal = throw LlmUnavailable("daily enrichment budget used up")
        }
        val stranded = mutableListOf<Int>()
        val hits = (0 until 5).map { hit("mcp", "https://github.com/a/r$it") }
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
        val run = RunScout(listOf(c), history, catalog, EnrichResource(model, store, clock), clock, NO_LOCK, maxEnrichmentsPerRun = 3,
            backlog = backlog { limit -> stranded += limit; listOf(UUID.randomUUID()) }).runNow()!!.single()
        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(0, run.enrichmentsQueued)
        assertEquals(listOf(EnrichmentOutcome.LLM_UNAVAILABLE), saved)
        assertEquals(emptyList(), stranded)
    }

    @Test
    fun `a resource whose source text has not changed since its last successful run is not sent again`() {
        var calls = 0
        val saved = mutableListOf<EnrichmentOutcome>()
        val store = object : FakeEnrichmentStore() {
            override fun lastFingerprint(resourceId: UUID) = material().fingerprintFor(model(true))
            override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant,
                              result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {
                saved += outcome
            }
        }
        val model = object : EnrichmentModel {
            override val provider = "fake"
            override val model = "m"
            override val promptVersion = "enrich-v3"
            override fun propose(input: EnrichmentInput): EnrichmentProposal = EnrichmentProposal().also { calls++ }
        }
        val meters = SimpleMeterRegistry()
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(listOf(hit("mcp", "https://github.com/a/r1")), null, "cp") }

        val run = RunScout(listOf(c), history, catalog, EnrichResource(model, store, clock, meters = meters), clock, NO_LOCK)
            .runNow()!!.single()

        assertEquals(0, calls)
        assertEquals(emptyList(), saved)
        assertEquals(0, run.enrichmentsQueued)
        assertEquals(1.0, meters.get("helper2.scout.enrichment").tag("outcome", "UNCHANGED").counter().count())
    }

    @Test
    fun `an unchanged resource gives its slot back, so no-op checks do not use up the run's enrichments`() {
        val unchanged = mutableSetOf<UUID>()
        val store = object : FakeEnrichmentStore() {
            val enriched = mutableListOf<UUID>()
            override fun lastFingerprint(resourceId: UUID) = if (resourceId in unchanged) material().fingerprintFor(model(true)) else null
            override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant,
                              result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {
                enriched += resourceId
            }
        }
        val hits = (0 until 3).map { hit("mcp", "https://github.com/a/same$it") }
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
        val stranded = (0 until 4).map { UUID.randomUUID() }
        unchanged += stranded[0]
        val catalog = CatalogIndex { results, _ -> results.map { it.id.value }.also { ids -> unchanged += ids; changedIds += ids } }

        val run = RunScout(listOf(c), history, catalog, EnrichResource(model(true), store, clock), clock, NO_LOCK,
            maxEnrichmentsPerRun = 2, backlog = backlog { limit -> stranded.take(limit) }).runNow()!!.single()

        // Three changed records with the same source text and one unchanged backlog entry cost nothing:
        // both model calls go to the next backlog entries.
        assertEquals(0, run.enrichmentsQueued)
        assertEquals(stranded.subList(1, 3), store.enriched)
    }

    @Test
    fun `relationship targets are resolved against the catalog only for the names the model proposed`() {
        val target = UUID.randomUUID()
        val asked = mutableListOf<Collection<String>>()
        var checked: ValidationResult? = null
        val evidence = UUID.randomUUID()
        val store = object : FakeEnrichmentStore() {
            override fun load(resourceId: UUID) = EnrichmentMaterial(
                EnrichmentInput("pg-mcp", listOf(Material(evidence, URI("https://x.example"), NOW, "Postgres MCP server. Works with Claude Desktop."))),
                "fp",
            )
            override fun resourcesTitled(titles: Collection<String>, resourceId: UUID) =
                mapOf("claude desktop" to target).also { asked += titles }
            override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant,
                              result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {
                checked = result
            }
        }
        val model = object : EnrichmentModel {
            override val provider = "fake"
            override val model = "m"
            override val promptVersion = "enrich-v3"
            override fun propose(input: EnrichmentInput) = EnrichmentProposal(relationships = listOf(
                ProposedRelationship("COMPATIBLE_WITH", " Claude Desktop ", listOf(Citation(evidence.toString(), "Works with Claude Desktop"))),
            ))
        }

        EnrichResource(model, store, clock).enrich(UUID.randomUUID())

        assertEquals(listOf<Collection<String>>(listOf("Claude Desktop")), asked)
        assertEquals(target, checked!!.relationships.single().targetId)
    }

    @Test
    fun `disabled collector is skipped and recorded`() {
        val off = FakeCollector(descriptor("off", enabled = false)) { error("must not run") }
        assertEquals(RunStatus.SKIPPED, RunScout(listOf(off), history, catalog, null, clock, NO_LOCK).runNow()!!.single().status)
        assertEquals(0, off.seenCheckpoints.size)
    }

    private class RecordingEnrichmentStore : FakeEnrichmentStore() {
        val enriched = mutableListOf<UUID>()
        override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant, result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {
            enriched += resourceId
        }
    }

    private fun backlog(eligible: (List<UUID>) -> List<UUID> = { it }, pending: (Int) -> List<UUID>) = object : EnrichmentBacklog {
        override fun pending(limit: Int) = pending(limit)
        override fun eligible(resourceIds: List<UUID>) = eligible(resourceIds)
    }

    private fun model(available: Boolean) = object : EnrichmentModel {
        override val provider = if (available) "fake" else "none"
        override val model = "m"
        override val promptVersion = "enrich-v3"
        override val available = available
        override fun propose(input: EnrichmentInput): EnrichmentProposal =
            if (available) EnrichmentProposal() else throw LlmUnavailable("no model")
    }

    @Test
    fun `budget left after changed resources goes to the backlog, without enriching anything twice`() {
        val store = RecordingEnrichmentStore()
        val hits = (0 until 2).map { hit("mcp", "https://github.com/a/r$it") }
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
        val stranded = (0 until 5).map { UUID.randomUUID() }
        val requestedLimits = mutableListOf<Int>()
        val backlog = backlog { limit -> requestedLimits += limit; (changedIds.take(1) + stranded).take(limit) }

        RunScout(listOf(c), history, catalog, EnrichResource(model(true), store, clock), clock, NO_LOCK,
            maxEnrichmentsPerRun = 4, backlog = backlog).runNow()!!

        assertEquals(changedIds + stranded.take(2), store.enriched)
        assertEquals(listOf(4), requestedLimits)
    }

    @Test
    fun `changed resources without enough text are not sent to the model`() {
        val store = RecordingEnrichmentStore()
        val hits = (0 until 4).map { hit("hf", "https://huggingface.co/m/r$it") }
        val c = FakeCollector(descriptor("hf")) { CollectedBatch(hits, null, "cp") }
        val withText = mutableListOf<UUID>()
        val run = RunScout(listOf(c), history, catalog, EnrichResource(model(true), store, clock), clock, NO_LOCK,
            backlog = backlog(eligible = { ids -> ids.take(1).also { withText += it } }) { emptyList() }).runNow()!!.single()
        assertEquals(1, run.enrichmentsQueued)
        assertEquals(withText, store.enriched)
    }

    @Test
    fun `the technical reason of a failed or unavailable call is saved`() {
        val reasons = mutableListOf<Pair<EnrichmentOutcome, String?>>()
        val store = object : FakeEnrichmentStore() {
            override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant,
                              result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {
                reasons += outcome to failure
            }
        }
        fun throwing(e: RuntimeException) = object : EnrichmentModel {
            override val provider = "fake"
            override val model = "m"
            override val promptVersion = "enrich-v3"
            override fun propose(input: EnrichmentInput): EnrichmentProposal = throw e
        }
        EnrichResource(throwing(LlmUnavailable("provider unavailable: 429")), store, clock).enrich(UUID.randomUUID())
        EnrichResource(throwing(ModelAnswerInvalid("answer contains no JSON object")), store, clock).enrich(UUID.randomUUID())
        assertEquals<List<Pair<EnrichmentOutcome, String?>>>(listOf(
            EnrichmentOutcome.LLM_UNAVAILABLE to "LlmUnavailable: provider unavailable: 429",
            EnrichmentOutcome.FAILED to "ModelAnswerInvalid: answer contains no JSON object",
        ), reasons)
    }

    @Test
    fun `one budget is shared by all collectors in a run`() {
        val store = RecordingEnrichmentStore()
        val a = FakeCollector(descriptor("a")) { CollectedBatch((0 until 3).map { hit("a", "https://github.com/a/a$it") }, null, "cp") }
        val b = FakeCollector(descriptor("b")) { CollectedBatch((0 until 3).map { hit("b", "https://github.com/b/b$it") }, null, "cp") }
        val runs = RunScout(listOf(a, b), history, catalog, EnrichResource(model(true), store, clock), clock, NO_LOCK,
            maxEnrichmentsPerRun = 4).runNow()!!
        assertEquals(listOf(3, 1), runs.map { it.enrichmentsQueued })
        assertEquals(4, store.enriched.size)
    }

    @Test
    fun `backlog is left alone while no model is configured`() {
        val store = RecordingEnrichmentStore()
        var asked = false
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }
        RunScout(listOf(c), history, catalog, EnrichResource(model(false), store, clock), clock, NO_LOCK,
            backlog = backlog { asked = true; listOf(UUID.randomUUID()) }).runNow()!!
        assertEquals(false, asked)
        assertEquals(emptyList(), store.enriched)
    }

    @Test
    fun `a failing backlog query does not fail the collector runs`() {
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }
        val runs = RunScout(listOf(c), history, catalog, EnrichResource(model(true), RecordingEnrichmentStore(), clock), clock, NO_LOCK,
            backlog = backlog { error("db down") }).runNow()!!
        assertEquals(listOf(RunStatus.SUCCEEDED), runs.map { it.status })
    }

    @Test
    fun `an enrichment error after indexing does not fail the run or stop the other enrichments`() {
        val hits = (0 until 3).map { hit("mcp", "https://github.com/a/r$it") }
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(hits, null, "cp") }
        val enriched = mutableListOf<UUID>()
        val store = object : FakeEnrichmentStore() {
            override fun load(resourceId: UUID): EnrichmentMaterial {
                if (resourceId == changedIds.first()) throw IllegalStateException("connection reset")
                return material()
            }
            override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant,
                              result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {
                enriched += resourceId
            }
        }
        val scoutStore = history

        val run = RunScout(listOf(c), scoutStore, catalog, EnrichResource(model(true), store, clock), clock, NO_LOCK).runNow()!!.single()

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(3, run.itemsSeen)
        assertEquals(2, run.enrichmentsQueued)
        assertEquals(changedIds.drop(1), enriched)
        assertEquals("1 enrichment failed; the backlog retries it", run.message)
        assertEquals(ScoutCursor(NOW.toString(), null, null), scoutStore.cursors["mcp"])
    }

    @Test
    fun `a failing backlog enrichment does not stop the rest of the backlog`() {
        val stranded = (0 until 3).map { UUID.randomUUID() }
        val enriched = mutableListOf<UUID>()
        val store = object : FakeEnrichmentStore() {
            override fun load(resourceId: UUID): EnrichmentMaterial {
                if (resourceId == stranded[0]) throw IllegalStateException("connection reset")
                return material()
            }
            override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: java.time.Instant,
                              result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {
                enriched += resourceId
            }
        }
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }
        RunScout(listOf(c), history, catalog, EnrichResource(model(true), store, clock), clock, NO_LOCK,
            backlog = backlog { limit -> stranded.take(limit) }).runNow()!!
        assertEquals(stranded.drop(1), enriched)
    }

    @Test
    fun `a failing run-history store does not stop the other collectors`() {
        val store = object : ScoutStore by MemStore() {
            override fun save(run: RunSummary) = throw IllegalStateException("db down")
        }
        val a = FakeCollector(descriptor("a")) { CollectedBatch(listOf(hit("a", "https://github.com/a/x")), null, "cp") }
        val off = FakeCollector(descriptor("off", enabled = false)) { error("must not run") }
        val b = FakeCollector(descriptor("b")) { CollectedBatch(listOf(hit("b", "https://github.com/b/y")), null, "cp") }

        val started = RunScout(listOf(a, off, b), store, catalog, null, clock, NO_LOCK).tryStart()!!

        assertEquals(listOf(RunStatus.RUNNING, RunStatus.SKIPPED, RunStatus.RUNNING), started.map { it.status })
        assertEquals(listOf(1, 1), listOf(a, b).map { it.seenCheckpoints.size })
    }

    @Test
    fun `a collector that is not due is recorded as skipped and keeps its checkpoint`() {
        val store = history
        store.cursors["tavily"] = ScoutCursor("2026-10-20T00:00:00Z", null, null)
        val c = FakeCollector(descriptor("tavily")) { CollectedBatch.notDue() }
        val run = RunScout(listOf(c), store, catalog, null, clock, NO_LOCK).runNow()!!.single()
        assertEquals(RunStatus.SKIPPED, run.status)
        assertEquals("2026-10-20T00:00:00Z", store.cursors["tavily"]!!.checkpoint)
    }

    @Test
    fun `unfinished pass resumes from the saved cursor and checkpoints at the pass start`() {
        val first = java.time.Instant.parse("2026-10-26T10:00:00Z")
        val second = java.time.Instant.parse("2026-10-26T11:00:00Z")
        val movingClock = MovableClock(first)
        val seen = mutableListOf<Pair<String?, String?>>()
        val collector = object : Collector {
            override val descriptor = descriptor("mcp")
            override fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int): CollectedBatch {
                seen += checkpoint to resumeCursor
                // Budget hit on the first run; the second run finishes the pass.
                return if (resumeCursor == null) CollectedBatch(emptyList(), "p2", null) else CollectedBatch(emptyList(), null, "done")
            }
        }
        val store = history
        val runner = RunScout(listOf(collector), store, catalog, null, movingClock, NO_LOCK)

        runner.runNow()
        assertEquals(ScoutCursor(null, "p2", first.toString()), store.cursors["mcp"])
        movingClock.now = second
        runner.runNow()

        assertEquals(listOf<Pair<String?, String?>>(null to null, null to "p2"), seen)
        // Items updated while the pass ran are not missed: the next incremental run starts from the FIRST run.
        assertEquals(ScoutCursor(first.toString(), null, null), store.cursors["mcp"])
    }

    @Test
    fun `while another run holds the lock nothing is collected and the start is refused`() {
        var collected = 0
        val c = FakeCollector(descriptor("mcp")) { collected++; CollectedBatch(emptyList(), null, "cp1") }
        val store = history
        val held = object : RunLock {
            override fun <T : Any> runExclusively(task: Supplier<T>): T? = null
        }

        assertNull(RunScout(listOf(c), store, catalog, null, clock, held).tryStart())
        assertEquals(0, collected)
        assertEquals(emptyList(), store.recent(10))
    }

    @Test
    fun `a started run answers with its planned runs at once and collects in the background`() {
        val release = CountDownLatch(1)
        val slow = FakeCollector(descriptor("slow")) { release.await(10, TimeUnit.SECONDS); CollectedBatch(emptyList(), null, "cp") }
        val off = FakeCollector(descriptor("off", enabled = false)) { error("must not run") }
        val store = history
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val started = RunScout(listOf(slow, off), store, catalog, null, clock, NO_LOCK, launcher = threads).tryStart()!!

            assertEquals(listOf(RunStatus.RUNNING, RunStatus.SKIPPED), started.map { it.status })
            assertEquals(started, store.recent(10))
            release.countDown()
            while (store.runs.getValue(started[0].id).status == RunStatus.RUNNING) Thread.sleep(10)
            assertEquals(listOf(RunStatus.SUCCEEDED, RunStatus.SKIPPED), started.map { store.runs.getValue(it.id).status })
        }
    }

    @Test
    fun `runs left RUNNING by a stopped replica are closed as failed before the next run`() {
        val store = history
        val stale = RunSummary(UUID.randomUUID(), "mcp", RunStatus.RUNNING, NOW.minusSeconds(3600), null, 0, 0, 0, null)
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
        val held = object : RunLock {
            override fun <T : Any> runExclusively(task: Supplier<T>): T? = null
        }

        assertEquals(false, RunScout(emptyList(), store, catalog, null, clock, held).closeInterruptedRuns())
        assertEquals(RunStatus.RUNNING, store.runs.values.single().status)

        assertEquals(true, RunScout(emptyList(), store, catalog, null, clock, NO_LOCK).closeInterruptedRuns())
        assertEquals(RunStatus.FAILED, store.runs.values.single().status)
    }
    private fun candidate(url: String) = Candidate(CanonicalUrl.of(url)!!, "AI agents course", "A course on agents.", "Hugging Face Learn")

    @Test
    fun `candidates a collector returns are proposed for review under its source id and never indexed`() {
        val proposed = mutableListOf<Pair<String, List<Candidate>>>()
        val review = CandidateReview { sourceId, candidates -> proposed += sourceId to candidates; candidates.size }
        val found = listOf(candidate("https://huggingface.co/learn/agents-course"))
        val c = FakeCollector(descriptor("course-scout")) { CollectedBatch(emptyList(), null, "cp", candidates = found) }

        val run = RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review).runNow()!!.single()

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(listOf("course-scout" to found), proposed)
        assertEquals(emptyList(), changedIds)
        assertEquals(NOW.toString(), history.cursors["course-scout"]!!.checkpoint)
    }

    @Test
    fun `a batch without candidates opens no review`() {
        val review = CandidateReview { _, _ -> error("must not be called") }
        val c = FakeCollector(descriptor("mcp")) { CollectedBatch(emptyList(), null, "cp") }
        assertEquals(RunStatus.SUCCEEDED, RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review).runNow()!!.single().status)
    }

    @Test
    fun `a review list that fails fails the run and keeps the checkpoint, so the next pass proposes again`() {
        history.cursors["course-scout"] = ScoutCursor("2026-10-20T00:00:00Z", null, null)
        val review = CandidateReview { _, _ -> throw IllegalStateException("database down") }
        val c = FakeCollector(descriptor("course-scout")) {
            CollectedBatch(emptyList(), null, "cp", candidates = listOf(candidate("https://www.coursera.org/learn/ai")))
        }

        val run = RunScout(listOf(c), history, catalog, null, clock, NO_LOCK, review = review).runNow()!!.single()

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals("2026-10-20T00:00:00Z", history.cursors["course-scout"]!!.checkpoint)
    }
}
