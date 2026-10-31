package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.Drops
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.MemoryQuestionIndex
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.SimilarQuestions
import ai.helper2.guide.TableEmbedder
import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Result
import ai.helper2.platform.quota.QuotaCounter.Verdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.search.CachedHits
import ai.helper2.search.NOW
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.WebSearchQuota
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import ai.helper2.testing.MovableClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A question worded differently but read the same way gets the cached answer of its near twin (ADR 0041). */
class AskGuideSimilarTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = MovableClock(NOW)

    @AfterTest
    fun shutdown() = executor.shutdownNow().let { }

    /** A metered web source, so the test sees the asker's daily web searches (ADR 0037). */
    private val webCalls = AtomicInteger()
    private val web = object : SearchSource {
        override val descriptor = descriptor("tavily").copy(metered = true)
        override fun search(query: SourceQuery): List<SourceHit> {
            webCalls.incrementAndGet()
            return listOf(
                hit("tavily", "https://example.org/pdf-chat", title = "pdf-chat", type = ResourceType.PRODUCT,
                    description = "Chat with your PDF files, free and open source"),
            )
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
        quota = WebSearchQuota(counter, QuotaKeys("test-secret", clock)))

    /** Plans "free" only when the question says it, as the grounding of ADR 0032 keeps it; counts every call. */
    private class Model : GuideModel {
        var plans = 0
        var chooses = 0
        override val provider = "fake"
        override val fastModel = "fast"
        override val reasoningModel = "reasoning"
        override fun plan(request: String, path: AudiencePath?): GuideSearchPlan {
            plans++
            return GuideSearchPlan("pdf chat", listOfNotNull("free".takeIf { "free" in request.lowercase() }, "paid".takeIf { "paid" in request.lowercase() }))
        }
        override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>): GuideProposal {
            chooses++
            return GuideProposal(candidates.map { ProposedPick(it.key, "Chats with PDFs.", it.result.description!!) }, null, emptyList())
        }
        override fun verifyReasons(request: String, claims: List<ReasonClaim>) = claims.associate { it.key to true }
    }

    private val original = "a free tool to chat with pdfs"
    private val paraphrase = "free tool for chatting with pdf files"
    private val vectors = mapOf(
        original to floatArrayOf(1f, 0f, 0f),
        paraphrase to floatArrayOf(0.96f, 0.28f, 0f), // cosine 0.96
        "a paid tool to chat with pdfs" to floatArrayOf(0.99f, 0.141f, 0f), // close, but read with another constraint
        "a free tool to chat with spreadsheets" to floatArrayOf(0.6f, 0.8f, 0f), // cosine 0.6
    )
    private val model = Model()
    private val index = MemoryQuestionIndex()
    private val embedder = TableEmbedder(vectors)
    private val meters = SimpleMeterRegistry()

    private fun guide(similar: SimilarQuestions? = SimilarQuestions(0.95, "embed", embedder, index, clock)) =
        AskGuide(search, model, clock, cache = GuideAnswerCache(Duration.ofHours(24), 10, clock), meters = meters, similar = similar)

    private fun cache(result: String) = meters.counter("helper2.guide.cache", "result", result).count()
    private fun questions(outcome: String) = meters.counter("helper2.guide.questions", "outcome", outcome).count()

    @Test
    fun `a paraphrase read the same way gets the cached answer after the plan alone`() {
        val guide = guide()
        val first = guide.ask(original, null)

        val reused = guide.ask(paraphrase, null)

        assertEquals(2, model.plans, "the paraphrase is planned, to know how it is read")
        assertEquals(1, model.chooses, "but not answered again")
        assertEquals(1, webCalls.get(), "and not searched again")
        assertEquals(first.copy(webSearches = null), reused.copy(webSearches = null))
        assertEquals(first.generatedAt, reused.generatedAt, "the page states the age of the answer it shows")
        assertEquals(listOf(1.0, 1.0, 0.0), listOf(cache("miss"), cache("similar"), cache("exact")))
        assertEquals(listOf(1.0, 1.0), listOf(questions("answered"), questions("cached")))
    }

    @Test
    fun `a reused answer stays AI-marked and spends none of the asker's web searches`() {
        val guide = guide()
        guide.ask(original, null, client = "203.0.113.7")

        val reused = guide.ask(paraphrase, null, client = "198.51.100.1")

        assertTrue(reused.modelUsed && reused.recommendations.all { it.aiReasoned }, "the AI marking of ADR 0038 comes with the answer")
        assertEquals(3, reused.webSearches!!.remaining, "a guest's three web searches, untouched")
        assertEquals(1, webCalls.get())
    }

    @Test
    fun `a question read with another constraint, or too far away, is answered fresh`() {
        val guide = guide()
        guide.ask(original, null)

        guide.ask("a paid tool to chat with pdfs", null)
        guide.ask("a free tool to chat with spreadsheets", null)

        assertEquals(3, model.chooses)
        assertEquals(listOf(3.0, 0.0), listOf(cache("miss"), cache("similar")))
    }

    @Test
    fun `the reused answer is kept under the paraphrase's own key, but never indexed`() {
        val guide = guide()
        guide.ask(original, null)
        guide.ask(paraphrase, null)

        guide.ask(paraphrase, null)

        assertEquals(2, model.plans, "the third question is an exact hit: no plan call")
        assertEquals(1.0, cache("exact"))
        assertEquals(1, index.size, "a reused answer is not a new neighbour: no chain of near twins drifting away")
    }

    @Test
    fun `a reused answer expires when the original does`() {
        val guide = guide()
        guide.ask(original, null)
        clock.now = NOW.plus(Duration.ofHours(23))
        guide.ask(paraphrase, null)

        clock.now = NOW.plus(Duration.ofHours(24))
        guide.ask(paraphrase, null)

        assertEquals(2, model.chooses, "past the original's 24 h the paraphrase is answered fresh")
    }

    @Test
    fun `a question with a clarification or a removed part of its reading is never looked up`() {
        val guide = guide()
        guide.ask(original, null)

        guide.ask(paraphrase, null, clarification = "")
        guide.ask(paraphrase, null, drops = Drops(window = true))

        assertEquals(3, model.chooses)
        assertEquals(listOf(original), embedder.asked)
    }

    @Test
    fun `without a threshold no question is embedded`() {
        val guide = guide(similar = null)
        guide.ask(original, null)
        guide.ask(paraphrase, null)

        assertEquals(2, model.chooses)
        assertTrue(embedder.asked.isEmpty())
        assertEquals(listOf(2.0, 0.0), listOf(cache("miss"), cache("similar")))
    }

    @Test
    fun `a question that cannot be embedded is answered fresh and not indexed`() {
        val guide = guide()

        guide.ask("a question with no recorded vector", null)

        assertEquals(1, model.chooses)
        assertEquals(0, index.size)
        assertEquals(1.0, cache("miss"))
    }
}
