package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideAnswerStore
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonClaim
import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Result
import ai.helper2.platform.quota.QuotaCounter.Verdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.search.CachedHits
import ai.helper2.search.IntentClass
import ai.helper2.search.NOW
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.WebResultStore
import ai.helper2.search.WebSearchQuota
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The guide under the daily web searches (ADR 0037): one per question, its own count, freshness by intent. */
class AskGuideWebSearchTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @AfterTest
    fun shutdown() = executor.shutdownNow().let { }

    private val webCalls = AtomicInteger()
    private val web = object : SearchSource {
        override val descriptor = descriptor("tavily").copy(metered = true)
        override fun search(query: SourceQuery): List<SourceHit> {
            webCalls.incrementAndGet()
            return listOf(
                hit("tavily", "https://github.com/ollama/ollama", title = "ollama", type = ResourceType.PRODUCT,
                    description = "Get up and running with large language models locally. MIT License"),
                hit("tavily", "https://lmstudio.ai", title = "LM Studio", rank = 1, type = ResourceType.PRODUCT,
                    description = "Discover, download and run local LLMs on your computer with a desktop app"),
                hit("tavily", "https://ai.google.dev/gemini", title = "Gemini", rank = 2, type = ResourceType.MODEL,
                    published = "2023-12-06T00:00:00Z", description = "Gemini is a family of multimodal models developed by Google"),
                hit("tavily", "https://github.com/google-gemini/gemini-cli", title = "gemini-cli", rank = 3,
                    published = "2025-06-25T00:00:00Z", description = "An open-source AI agent that brings Gemini into your terminal"),
            )
        }
    }

    private val stored = mutableMapOf<Pair<String, IntentClass>, CachedHits>()
    private val store = object : WebResultStore {
        override fun find(sourceId: String, query: String, intentClass: IntentClass) = stored[query to intentClass]
        override fun replace(sourceId: String, query: String, intentClass: IntentClass, fetchedAt: Instant, hits: List<SourceHit>) {
            stored[query to intentClass] = CachedHits(hits, fetchedAt)
        }
    }

    private val counts = HashMap<Counter, Long>()
    private val counter = object : QuotaCounter {
        override fun take(own: Counter?, ownLimit: Int, global: Counter?, globalLimit: Long): Result {
            val used = own?.let { counts[it] ?: 0 } ?: 0
            if (own != null && used >= ownLimit) return Result(Verdict.LIMIT_REACHED, used)
            own?.let { counts[it] = used + 1 }
            return Result(Verdict.TAKEN, used + 1)
        }
        override fun giveBack(counter: Counter) {}
        override fun used(counter: Counter) = counts[counter] ?: 0
        override fun delete(keys: Collection<String>) {}
    }

    private val noCache = object : SearchCache {
        override fun get(key: String): CachedHits? = null
        override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

    private val search = SearchResources(listOf(web), noCache, ResultObserver { emptyMap() }, executor, clock,
        webResults = store, quota = WebSearchQuota(counter, QuotaKeys("test-secret", clock)))

    private class Model(val plan: GuideSearchPlan) : GuideModel {
        override val provider = "fake"
        override val fastModel = "fast"
        override val reasoningModel = "reasoning"
        override fun plan(request: String, path: AudiencePath?) = plan
        override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>) =
            GuideProposal(candidates.map { ProposedPick(it.key, "Runs models locally.", it.result.description!!) }, null, emptyList())
        override fun verifyReasons(request: String, claims: List<ReasonClaim>) = claims.associate { it.key to true }
    }

    @Test
    fun `an ask-back round trip is two questions, the reply searching anew and takes its own web search`() {
        val released = object : GuideModel {
            override val provider = "fake"
            override val fastModel = "fast"
            override val reasoningModel = "reasoning"
            override fun plan(request: String, path: AudiencePath?) =
                GuideSearchPlan("Gemini", intent = GuideIntent.WHEN, subjects = listOf("Gemini"))
            override fun plan(request: String, path: AudiencePath?, clarification: String?) =
                GuideSearchPlan("gemini cli", intent = GuideIntent.WHEN, subjects = listOf("gemini cli"))
            override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>) = error("no choose for a date")
        }
        val guide = AskGuide(search, released, clock)

        val asked = guide.ask("when was Gemini released?", null, client = "192.0.2.9")
        assertEquals(GuideStatus.NEEDS_CLARIFICATION, asked.status)
        assertEquals(2, asked.webSearches!!.remaining)

        val answered = guide.ask("when was Gemini released?", null, client = "192.0.2.9", clarification = "gemini cli (tool)")
        assertEquals(GuideStatus.OK, answered.status)
        assertEquals(1, answered.webSearches!!.remaining)
        assertEquals(2, webCalls.get())
    }

    private val alternatives = Model(GuideSearchPlan("local LLM runner", intent = GuideIntent.ALTERNATIVES, subjects = listOf("Ollama")))

    @Test
    fun `a question that searches twice takes one web search and states what the guide has left`() {
        val answer = AskGuide(search, alternatives, clock).ask("alternatives to Ollama", null, client = "203.0.113.7")

        assertEquals(2, webCalls.get(), "the subject lookup and the picks: two credits")
        assertEquals(2, answer.webSearches!!.remaining)
        assertEquals(3, search.webSearchesLeft(ai.helper2.search.Asker(ai.helper2.search.Feature.SEARCH, "203.0.113.7"))!!.remaining)
    }

    @Test
    fun `a cached answer shows each asker their own web searches left`() {
        val guide = AskGuide(search, alternatives, clock, cache = GuideAnswerCache(Duration.ofHours(24), 10, clock))
        guide.ask("alternatives to Ollama", null, client = "203.0.113.7")

        val cached = guide.ask("alternatives to Ollama", null, client = "198.51.100.1")

        assertEquals(3, cached.webSearches!!.remaining)
        assertEquals(2, webCalls.get())
    }

    @Test
    fun `a stored or shared answer keeps no asker`() {
        val stored = mutableListOf<GuideAnswer>()
        val store = object : GuideAnswerStore {
            override fun get(key: String): GuideAnswer? = null
            override fun put(key: String, answer: GuideAnswer, ttl: Duration) { stored += answer }
        }
        val guide = AskGuide(search, alternatives, clock, cache = GuideAnswerCache(Duration.ofHours(24), 10, clock, store))
        val user = UUID.randomUUID()
        guide.ask("alternatives to Ollama", null, client = "203.0.113.7", userId = user)

        val cached = guide.ask("alternatives to Ollama", null, client = "198.51.100.1")

        assertNull(stored.single().search.request.asker, "Redis must not hold the address or the user id")
        assertNull(cached.search.request.asker, "another asker must not see the first one")
    }

    @Test
    fun `an answer on web results is fresh as long as they are, by the question's intent`() {
        val general = AskGuide(search, alternatives, clock).ask("alternatives to Ollama", null, client = "c")
        assertEquals(NOW.plus(Duration.ofHours(48)), general.webFreshUntil)

        val learn = Model(GuideSearchPlan("local LLM course", intent = GuideIntent.LEARN))
        val timeBound = AskGuide(search, learn, clock).ask("a course on running local LLMs", null, client = "c")
        assertEquals(NOW.plus(Duration.ofHours(24)), timeBound.webFreshUntil)
        assertEquals(setOf(IntentClass.GENERAL, IntentClass.TIME_BOUND), stored.keys.map { it.second }.toSet())
    }
}
