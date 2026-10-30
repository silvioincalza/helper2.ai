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
import ai.helper2.shared.DateFact
import ai.helper2.shared.ResourceType
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import ai.helper2.search.NoticeCode
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    private var hits = listOf(
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
        var explained = 0
        override fun explain(request: String, subject: String, candidates: List<Candidate>): ExplanationProposal {
            explained++
            return explain(candidates)
        }
        override fun verifyReasons(request: String, claims: List<ReasonClaim>) = claims.associate { it.key to true }
    }

    private fun guide(model: GuideModel?) = AskGuide(search, model, clock)

    private val meters = SimpleMeterRegistry()
    private val observations = ObservationRegistry.create().also {
        it.observationConfig().observationHandler(DefaultMeterObservationHandler(meters))
    }

    private fun steps(intent: String) =
        meters.find("helper2.guide.step").tag("intent", intent).timers().map { it.id.getTag("step") }.toSet()

    private fun ollamaVsLmStudio() = Model(
        GuideSearchPlan("local LLM runner", intent = GuideIntent.COMPARE, subjects = listOf("Ollama", "LM Studio")),
        compare = { s ->
            val ollama = s.first { it.result.title == "ollama" }.key
            ComparisonProposal(listOf(ProposedRow("Licence", listOf(ProposedCell(ollama, "MIT", "MIT License")))))
        },
    )

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

    /** Two own entries of different kinds for one name: the model and the command-line tool. */
    private val gemini = listOf(
        hit("s", "https://ai.google.dev/gemini", title = "Gemini", rank = 8, type = ResourceType.MODEL, published = "2023-12-06T00:00:00Z",
            description = "Gemini is a family of multimodal large language models developed by Google"),
        hit("s", "https://github.com/google-gemini/gemini-cli", title = "google-gemini/gemini-cli", rank = 9, published = "2025-06-25T00:00:00Z",
            description = "An open-source AI agent that brings the power of Gemini directly into your terminal"),
    )

    @Test
    fun `a name with own entries of two kinds is asked back after its own search and the reply goes straight to an answer`() {
        hits = hits + gemini
        val model = Model(GuideSearchPlan("Gemini", intent = GuideIntent.WHAT_IS, subjects = listOf("Gemini")))
        val first = guide(model).ask("what is Gemini?", null)
        assertEquals(GuideStatus.NEEDS_CLARIFICATION, first.status)
        assertEquals(Clarification("Which Gemini do you mean?", listOf("Gemini (model)", "gemini cli (tool)")), first.clarification)
        assertEquals(1, queries.size, "one search, the one the explanation needs anyway")
        assertEquals(0, model.explained)

        val chosen = guide(model).ask("what is Gemini?", null, clarification = "gemini cli (tool)")
        assertNotEquals(GuideStatus.NEEDS_CLARIFICATION, chosen.status)
        assertEquals(1, model.explained)
        val anyway = guide(model).ask("what is Gemini?", null, clarification = "")
        assertNotEquals(GuideStatus.NEEDS_CLARIFICATION, anyway.status)
    }

    @Test
    fun `alternatives, release dates and a bare name are checked for namesakes but a name inside a task is not`() {
        hits = hits + gemini
        fun ask(plan: GuideSearchPlan, text: String) = guide(Model(plan, choose = ::pickAll)).ask(text, null).status
        val asked = GuideStatus.NEEDS_CLARIFICATION
        assertEquals(asked, ask(GuideSearchPlan("AI assistant", intent = GuideIntent.ALTERNATIVES, subjects = listOf("Gemini")), "alternatives to Gemini"))
        assertEquals(asked, ask(GuideSearchPlan("Gemini", intent = GuideIntent.WHEN, subjects = listOf("Gemini")), "when was Gemini released?"))
        assertEquals(asked, ask(GuideSearchPlan("Gemini", subjects = listOf("Gemini")), "Gemini?"))
        assertNotEquals(asked, ask(GuideSearchPlan("chat UI Gemini", subjects = listOf("Gemini")), "a chat UI for Gemini"))
        assertNotEquals(asked, ask(GuideSearchPlan("Gemini", intent = GuideIntent.NEWEST, subjects = listOf("Gemini")), "latest Gemini tools"))
    }

    @Test
    fun `a question that already names the kind of thing is not asked back`() {
        hits = hits + gemini
        fun ask(plan: GuideSearchPlan, text: String) = guide(Model(plan, choose = ::pickAll)).ask(text, null).status
        val asked = GuideStatus.NEEDS_CLARIFICATION
        assertNotEquals(asked, ask(GuideSearchPlan("Gemini", intent = GuideIntent.WHAT_IS, subjects = listOf("Gemini")), "what is the Gemini model?"))
        assertNotEquals(asked, ask(GuideSearchPlan("Gemini", intent = GuideIntent.ALTERNATIVES, subjects = listOf("Gemini")), "alternatives to the Gemini CLI tool"))
        assertNotEquals(asked, ask(GuideSearchPlan("Gemini", intent = GuideIntent.WHAT_IS, subjects = listOf("Gemini")), "cos'è il modello Gemini?"))
    }

    @Test
    fun `a planner's question back on a name with one kind of own entry is dropped, and without a planner nothing is asked`() {
        val clarify = Clarification("Which Ollama?", listOf("the app", "the library"))
        val model = Model(GuideSearchPlan("Ollama", intent = GuideIntent.WHAT_IS, subjects = listOf("Ollama"), clarify = clarify))
        assertNotEquals(GuideStatus.NEEDS_CLARIFICATION, guide(model).ask("what is Ollama?", null).status)
        hits = hits + gemini
        assertEquals(GuideStatus.LLM_UNAVAILABLE, guide(null).ask("what is Gemini?", null).status)
    }

    private fun event(url: String, title: String, start: String, rank: Int) =
        hit("s", url, title = title, rank = rank, type = ResourceType.EVENT, description = "$title, a two-day event")
            .copy(eventStart = DateFact.scheduledDay(LocalDate.parse(start), "s"), location = "Zurich")

    @Test
    fun `a when question about an event searches only events and answers soonest first`() {
        hits = hits + listOf(
            hit("s", "https://github.com/acme/hackathon", title = "hackathon", rank = 8, description = "A hackathon starter kit"),
            event("https://example.com/hack-zurich", "AI Hackathon Zurich", "2026-11-20", 9),
            event("https://example.com/hack-milan", "AI Hackathon Milan", "2026-11-05", 10),
        )
        val model = Model(GuideSearchPlan("AI hackathon", intent = GuideIntent.WHEN, webQuery = "upcoming AI hackathons"))
        val a = guide(model).ask("when is the next AI hackathon?", null)
        assertEquals(setOf(ResourceType.EVENT), queries.single().types)
        assertEquals("upcoming AI hackathons", queries.single().webQuery)
        assertEquals(listOf("AI Hackathon Milan", "AI Hackathon Zurich"), a.recommendations.map { it.result.title })
        assertTrue(a.recommendations.first().reason.startsWith("On 2026-11-05, Zurich"))
    }

    @Test
    fun `an event named only by the planner's query is searched as an event`() {
        hits = hits + event("https://neurips.cc/2026", "NeurIPS 2026", "2026-12-06", 8)
        val model = Model(GuideSearchPlan("NeurIPS conference", intent = GuideIntent.WHEN, subjects = listOf("NeurIPS")))
        val a = guide(model).ask("when is NeurIPS?", null)
        assertEquals(setOf(ResourceType.EVENT), queries.single().types)
        assertEquals("NeurIPS 2026", a.recommendations.single().result.title)
    }

    @Test
    fun `when a product started is a release date, not an event`() {
        val model = Model(GuideSearchPlan("Ollama release date", intent = GuideIntent.WHEN, subjects = listOf("Ollama")))
        guide(model).ask("when did Ollama start?", null)
        assertNotEquals(setOf(ResourceType.EVENT), queries.first().types)
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
        assertEquals(emptySet(), queries.single().types, "a release is searched over every type")
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

    @Test
    fun `compare, explain and dates questions time each of their steps`() {
        val observed = { model: GuideModel -> AskGuide(search, model, clock, observations = observations) }
        observed(ollamaVsLmStudio()).ask("Ollama vs LM Studio", null)
        observed(Model(GuideSearchPlan("Ollama", intent = GuideIntent.WHAT_IS, subjects = listOf("Ollama")))).ask("what is Ollama?", null)
        observed(Model(GuideSearchPlan("agent-kit", intent = GuideIntent.WHEN, subjects = listOf("agent-kit")))).ask("when was agent-kit released?", null)

        assertEquals(setOf("plan", "search", "compare", "validate", "verify"), steps("compare"))
        assertEquals(setOf("plan", "search", "explain", "validate"), steps("what_is"))
        assertEquals(setOf("plan", "search"), steps("when"))
    }

    /** Each subject search waits until the other has started too: run one after the other, neither would see it. */
    private class Rendezvous(private val hits: List<SourceHit>, private val subjects: Int) : SearchSource {
        val together = CountDownLatch(subjects)
        var allMet = true
        override val descriptor = descriptor("r", timeout = Duration.ofSeconds(3))
        override fun search(query: SourceQuery): List<SourceHit> {
            together.countDown()
            if (!together.await(1, TimeUnit.SECONDS)) allMet = false
            return hits
        }
    }

    @Test
    fun `the subjects of a comparison are searched at the same time`() {
        val source = Rendezvous(hits, subjects = 2)
        val search = SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)

        val a = AskGuide(search, ollamaVsLmStudio(), clock).ask("Ollama vs LM Studio", null)

        assertTrue(source.allMet)
        assertEquals(listOf("ollama", "LM Studio"), assertNotNull(a.comparison).subjects.map { it.result?.title })
    }

    @Test
    fun `a subject whose search misses the deadline is not found`() {
        val slowForLmStudio = object : SearchSource {
            override val descriptor = descriptor("s", timeout = Duration.ofSeconds(3))
            override fun search(query: SourceQuery): List<SourceHit> {
                if (query.text.contains("lm studio", ignoreCase = true)) Thread.sleep(1_000)
                return hits
            }
        }
        val search = SearchResources(listOf(slowForLmStudio), noCache, ResultObserver { emptyMap() }, executor, clock)

        val a = AskGuide(search, ollamaVsLmStudio(), clock, subjectSearchDeadline = Duration.ofMillis(200))
            .ask("Ollama vs LM Studio", null)

        assertNull(a.comparison)
        assertTrue(a.notices.any { it.code == NoticeCode.GUIDE_SUBJECT_NOT_FOUND && it.message.startsWith("LM Studio") })
    }
}
