package ai.helper2.guide

import ai.helper2.guide.usecase.AskGuide
import ai.helper2.search.CachedHits
import ai.helper2.search.usecase.SearchResources
import ai.helper2.search.NOW
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import ai.helper2.testing.MovableClock
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The answer cache reads through to a durable store, so a restart does not cost the model calls again. */
class GuideAnswerCacheStoreTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = MovableClock(NOW)

    @AfterTest
    fun shutdown() = executor.shutdownNow().let { }

    /** What Redis would hold: survives a new GuideAnswerCache, as it survives a pod restart. */
    private class MapStore : GuideAnswerStore {
        val entries = mutableMapOf<String, GuideAnswer>()
        var puts = 0
        override fun get(key: String) = entries[key]
        override fun put(key: String, answer: GuideAnswer, ttl: Duration) {
            puts++
            entries[key] = answer
        }
    }

    private class DownStore : GuideAnswerStore {
        override fun get(key: String): GuideAnswer = throw IllegalStateException("redis down")
        override fun put(key: String, answer: GuideAnswer, ttl: Duration) = throw IllegalStateException("redis down")
    }

    private class CountingModel : GuideModel {
        var calls = 0
        override val provider = "fake"
        override val fastModel = "fast"
        override val reasoningModel = "reasoning"
        override fun plan(request: String, path: AudiencePath?): GuideSearchPlan {
            calls++
            return GuideSearchPlan("speech recognition")
        }

        override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>): GuideProposal {
            calls++
            val whisper = candidates.first { it.result.title == "whisper-small" }.key
            return GuideProposal(listOf(ProposedPick(whisper, "Self-hostable.", "Speech recognition model you can self-host")), null, emptyList())
        }

        override fun verifyReasons(request: String, claims: List<ReasonClaim>) = claims.associate { it.key to true }
    }

    private val source = object : SearchSource {
        override val descriptor = descriptor("hf")
        override fun search(query: SourceQuery): List<SourceHit> = listOf(
            hit(
                "hf", "https://huggingface.co/acme/whisper-small", title = "whisper-small", type = ResourceType.MODEL,
                description = "Speech recognition model you can self-host, released under the MIT License",
            ),
        )
    }

    private val noCache = object : SearchCache {
        override fun get(key: String) = null
        override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

    private val search = SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)
    private val need = "a speech recognition model I can self-host"
    private val ttl = Duration.ofHours(24)

    @Test
    fun `an answer stored before a restart is served after it without calling the model`() {
        val store = MapStore()
        val model = CountingModel()
        val before = AskGuide(search, model, clock, cache = GuideAnswerCache(ttl, 10, clock, store))
        val first = before.ask(need, null)

        val afterRestart = AskGuide(search, model, clock, cache = GuideAnswerCache(ttl, 10, clock, store))
        val second = afterRestart.ask(need, null)

        assertEquals(2, model.calls)
        assertEquals(first, second)
    }

    @Test
    fun `a stored answer expires ttl after it was generated, not after it was read back`() {
        val store = MapStore()
        GuideAnswerCache(ttl, 10, clock, store).put("k", AskGuide(search, CountingModel(), clock).ask(need, null))

        clock.now = NOW.plus(Duration.ofHours(23))
        val freshCache = GuideAnswerCache(ttl, 10, clock, store)
        assertTrue(freshCache.get("k") != null)

        clock.now = NOW.plus(Duration.ofHours(24))
        assertNull(freshCache.get("k"))
        assertNull(GuideAnswerCache(ttl, 10, clock, store).get("k"))
    }

    @Test
    fun `a degraded answer is never written to the store`() {
        val store = MapStore()
        val degraded = AskGuide(search, null, clock).ask(need, null)

        GuideAnswerCache(ttl, 10, clock, store).put("k", degraded)

        assertEquals(0, store.puts)
    }

    @Test
    fun `a failing store is a miss and never breaks the guide`() {
        val model = CountingModel()
        val guide = AskGuide(search, model, clock, cache = GuideAnswerCache(ttl, 10, clock, DownStore()))

        val first = guide.ask(need, null)
        val second = guide.ask(need, null)

        assertTrue(first.recommendations.any { it.aiReasoned })
        assertEquals(first, second)
        assertEquals(2, model.calls)
    }
}
