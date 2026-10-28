package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.Clarification
import ai.helper2.guide.ComparisonProposal
import ai.helper2.guide.Drops
import ai.helper2.guide.ExplanationProposal
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.PlannedWindow
import ai.helper2.guide.ProposedCell
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ProposedRow
import ai.helper2.guide.ProposedSentence
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.WindowUnit
import ai.helper2.search.CachedHits
import ai.helper2.search.NOW
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0032: each intent's retrieval and answer, with a scripted model and a recording source. Today is 2026-10-22. */
class AskGuideIntentsTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @AfterTest
    fun shutdown() = executor.shutdownNow().let { }

    private val queries = ConcurrentLinkedQueue<SourceQuery>()

    private val hits = listOf(
        hit("s", "https://github.com/acme/agent-kit", title = "agent-kit", published = "2026-10-20T00:00:00Z",
            description = "Agent framework for building tool-using assistants in Python, released this autumn"),
        hit("s", "https://github.com/acme/rag-box", title = "rag-box", rank = 1, published = "2026-10-01T00:00:00Z",
            description = "Retrieval augmented generation toolkit with connectors for many vector databases"),
        hit("s", "https://github.com/acme/old-tool", title = "old-tool", rank = 2, published = "2025-01-01T00:00:00Z",
            description = "An older command line helper for prompting large language models from scripts"),
        hit("s", "https://www.npmjs.com/package/undated", title = "undated", rank = 3,
            description = "A package whose source states no creation date at all for anyone to read"),
        hit("s", "https://github.com/ollama/ollama", title = "ollama", rank = 4, type = ResourceType.PRODUCT,
            description = "Get up and running with large language models locally. MIT License"),
        hit("s", "https://lmstudio.ai", title = "LM Studio", rank = 5, type = ResourceType.PRODUCT,
            description = "Discover, download and run local LLMs on your computer with a desktop app"),
        hit("s", "https://www.npmjs.com/package/ollama-js-helper", title = "ollama-js-helper", rank = 6,
            description = "Small helper around the ollama HTTP API for node scripts and bots"),
        hit("s", "https://neurips.cc", title = "NeurIPS 2026", rank = 7, type = ResourceType.EVENT,
            description = "The Conference on Neural Information Processing Systems"),
    )

    private val source = object : SearchSource {
        override val descriptor = descriptor("s").copy(filtersByDate = true, browses = true)
        override fun search(query: SourceQuery): List<SourceHit> {
            queries += query
            return hits
        }
    }

    private val noCache = object : SearchCache {
        override fun get(key: String) = null
        override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

    private val search = SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)

    private class Model(
        val plan: GuideSearchPlan,
        val choose: (List<Candidate>) -> GuideProposal = { GuideProposal(emptyList(), null, emptyList()) },
        val compare: (List<Candidate>) -> ComparisonProposal = { ComparisonProposal(emptyList()) },
        val explain: (List<Candidate>) -> ExplanationProposal = { ExplanationProposal(emptyList()) },
    ) : GuideModel {
        override val provider = "fake"
        override val fastModel = "fast"
        override val reasoningModel = "reasoning"
        var clarificationSeen: String? = "unset"
        var chosenFrom: List<Candidate> = emptyList()
        override fun plan(request: String, path: AudiencePath?) = plan
        override fun plan(request: String, path: AudiencePath?, clarification: String?): GuideSearchPlan {
            clarificationSeen = clarification
            return plan
        }
        override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>): GuideProposal {
            chosenFrom = candidates
            thought = false
            return choose(candidates)
        }
        var thought: Boolean? = null
        override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>, think: Boolean): GuideProposal {
            chosenFrom = candidates
            thought = think
            return choose(candidates)
        }
        override fun compare(request: String, subjects: List<Candidate>) = compare(subjects)
        override fun explain(request: String, subject: String, candidates: List<Candidate>) = explain(candidates)
        override fun verifyReasons(request: String, claims: List<ReasonClaim>) = claims.associate { it.key to true }
    }

    private fun guide(model: GuideModel?) = AskGuide(search, model, clock)

    private fun pickAll(c: List<Candidate>) = GuideProposal(
        c.map { ProposedPick(it.key, "Fits the need.", it.result.description!!) }, null, emptyList(),
    )

    @Test
    fun `the reproduced question browses the last 30 days and answers newest first, without old or undated tools`() {
        val model = Model(
            GuideSearchPlan("AI tools", intent = GuideIntent.NEWEST, window = PlannedWindow(1, WindowUnit.MONTH)),
            choose = ::pickAll,
        )
        val a = guide(model).ask("what are the last ai tools born in the last month?", null)
        val q = queries.single()
        assertTrue(q.browse)
        assertEquals(LocalDate.of(2026, 9, 22), q.publishedAfter)
        assertEquals(GuideIntent.NEWEST, a.intent)
        assertEquals(LocalDate.of(2026, 9, 22), a.window?.from)
        assertEquals(listOf("agent-kit", "rag-box"), a.recommendations.map { it.result.title })
        assertTrue(model.chosenFrom.none { it.result.title == "old-tool" || it.result.title == "undated" })
        // A broad "what is new" has no subject to match: the chooser thinks; a plain suggestion does not.
        assertEquals(true, model.thought)
        val plain = Model(GuideSearchPlan("MCP server postgres"), choose = ::pickAll)
        guide(plain).ask("an MCP server for Postgres", null)
        assertEquals(false, plain.thought)
    }

    @Test
    fun `without a model the backup rules still read the window and date the answer`() {
        val a = guide(null).ask("AI tools from the last month", null)
        assertEquals(GuideIntent.NEWEST, a.intent)
        assertEquals(GuideStatus.LLM_UNAVAILABLE, a.status)
        assertTrue(a.recommendations.all { it.result.published.value != null && it.result.title in setOf("agent-kit", "rag-box") })
    }

    @Test
    fun `a vague question is asked back without searching, and never twice`() {
        val clarify = Clarification("New tools for what?", listOf("coding", "agents"))
        val model = Model(GuideSearchPlan("AI tools", intent = GuideIntent.NEWEST, clarify = clarify))
        val first = guide(model).ask("new tools?", null)
        assertEquals(GuideStatus.NEEDS_CLARIFICATION, first.status)
        assertEquals(clarify, first.clarification)
        assertTrue(queries.isEmpty())

        val second = guide(model).ask("new tools?", null, clarification = "agents")
        assertEquals("agents", model.clarificationSeen)
        assertTrue(second.status != GuideStatus.NEEDS_CLARIFICATION)
        assertTrue(queries.isNotEmpty())
    }

    @Test
    fun `a question with a period is never asked back`() {
        val model = Model(
            GuideSearchPlan("AI tools", window = PlannedWindow(1, WindowUnit.MONTH), clarify = Clarification("For what?", listOf("a", "b"))),
            choose = ::pickAll,
        )
        val a = guide(model).ask("AI tools born last month", null)
        assertEquals(GuideIntent.NEWEST, a.intent)
        assertNull(a.clarification)
    }

    @Test
    fun `dropping the window answers as a plain suggestion over every date`() {
        val model = Model(GuideSearchPlan("AI tools", intent = GuideIntent.NEWEST, window = PlannedWindow(1, WindowUnit.MONTH)), choose = ::pickAll)
        val a = guide(model).ask("AI tools born last month", null, drops = Drops(window = true, intent = true))
        assertEquals(GuideIntent.SUGGEST, a.intent)
        assertNull(a.window)
        assertNull(queries.single().publishedAfter)
    }

    @Test
    fun `a period or subjects the user never wrote are ignored, so nothing is filtered by an invented window`() {
        val model = Model(
            GuideSearchPlan("MCP server postgres", intent = GuideIntent.NEWEST, subjects = listOf("Supabase"),
                window = PlannedWindow(1, WindowUnit.MONTH)),
            choose = ::pickAll,
        )
        val a = guide(model).ask("an open source MCP server to query Postgres", null)
        assertEquals(GuideIntent.SUGGEST, a.intent)
        assertNull(a.window)
        assertEquals(emptyList(), a.subjects)
        assertNull(queries.single().publishedAfter)
    }

    @Test
    fun `alternatives never pick the subject itself`() {
        val model = Model(GuideSearchPlan("local LLM runner", intent = GuideIntent.ALTERNATIVES, subjects = listOf("Ollama")), choose = ::pickAll)
        val a = guide(model).ask("alternatives to Ollama", null)
        assertTrue(model.chosenFrom.isNotEmpty())
        assertTrue(model.chosenFrom.none { it.result.title == "ollama" })
        assertTrue(a.recommendations.none { it.result.title == "ollama" })
    }

    @Test
    fun `a comparison keeps only cells quoted from that subject's own text`() {
        val model = Model(
            GuideSearchPlan("local LLM runner", intent = GuideIntent.COMPARE, subjects = listOf("Ollama", "LM Studio")),
            compare = { s ->
                val ollama = s.first { it.result.title == "ollama" }.key
                val lm = s.first { it.result.title == "LM Studio" }.key
                ComparisonProposal(
                    listOf(
                        ProposedRow("Licence", listOf(ProposedCell(ollama, "MIT", "MIT License"), ProposedCell(lm, "Apache", "Apache 2.0"))),
                        ProposedRow("What it is", listOf(ProposedCell(lm, "A desktop app for local LLMs", "run local LLMs on your computer"))),
                    ),
                )
            },
        )
        val a = guide(model).ask("Ollama vs LM Studio", null)
        val c = assertNotNull(a.comparison)
        assertEquals(listOf("Ollama", "LM Studio"), c.subjects.map { it.name })
        assertEquals("ollama", c.subjects[0].result?.title)
        val licence = c.rows.first { it.aspect == "Licence" }
        assertEquals("MIT", licence.cells[0].text)
        // "Apache 2.0" is not in LM Studio's text: not stated, never guessed.
        assertNull(licence.cells[1].text)
        assertEquals(1, a.rejected)
    }

    @Test
    fun `an explanation keeps only quoted sentences and leads with the subject's own card`() {
        val model = Model(
            GuideSearchPlan("Ollama", intent = GuideIntent.WHAT_IS, subjects = listOf("Ollama")),
            explain = { c ->
                val own = c.first { it.result.title == "ollama" }.key
                ExplanationProposal(
                    listOf(
                        ProposedSentence("Ollama runs large language models on your own machine.", own,
                            "Get up and running with large language models locally."),
                        ProposedSentence("It is the fastest runner.", own, "fastest runner available anywhere today"),
                    ),
                )
            },
        )
        val a = guide(model).ask("what is Ollama?", null)
        val e = assertNotNull(a.explanation)
        assertEquals(1, e.sentences.size)
        assertEquals("ollama", a.recommendations.first().result.title)
        assertEquals(1, a.rejected)
    }

    @Test
    fun `when a release happened comes from the source's creation date, without a model call`() {
        val model = Model(GuideSearchPlan("agent-kit", intent = GuideIntent.WHEN, subjects = listOf("agent-kit")))
        val a = guide(model).ask("when was agent-kit released?", null)
        assertEquals(GuideStatus.OK, a.status)
        assertTrue(a.recommendations.single().reason.startsWith("Created 2026-10-20"))
    }

    @Test
    fun `courses are retrieved only for a learning question`() {
        val model = Model(GuideSearchPlan("AI certification", intent = GuideIntent.LEARN), choose = ::pickAll)
        guide(model).ask("AI certifications for beginners", null)
        assertEquals(emptySet(), queries.single().types - setOf(ResourceType.COURSE))
        assertTrue(queries.single().types.contains(ResourceType.COURSE))
    }

    @Test
    fun `a learning question with a period searches only courses created in it`() {
        val model = Model(
            GuideSearchPlan("LangChain course", intent = GuideIntent.LEARN, window = PlannedWindow(1, WindowUnit.MONTH)),
            choose = ::pickAll,
        )
        val a = guide(model).ask("LangChain courses released last month", null)
        assertEquals(GuideIntent.LEARN, a.intent)
        assertEquals(LocalDate.of(2026, 9, 22), queries.single().publishedAfter)
        assertTrue(a.recommendations.all { it.result.published.value != null })
    }
}
