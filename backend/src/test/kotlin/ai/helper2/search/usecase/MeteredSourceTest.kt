package ai.helper2.search.usecase

import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Result
import ai.helper2.platform.quota.QuotaCounter.Verdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.search.Asker
import ai.helper2.search.CachedHits
import ai.helper2.search.Feature
import ai.helper2.search.IntentClass
import ai.helper2.search.NOW
import ai.helper2.search.QuotaTier
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchMetrics
import ai.helper2.search.SearchRequest
import ai.helper2.search.SearchSource
import ai.helper2.search.ServedFrom
import ai.helper2.search.SkipReason
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceOutcome
import ai.helper2.search.SourceQuery
import ai.helper2.search.WebResultStore
import ai.helper2.search.WebSearchQuota
import ai.helper2.search.WebSearchesLeft
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.testing.MovableClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A source whose live calls cost credits (Tavily, ADR 0037): store first, then a live call inside the daily quota. */
class MeteredSourceTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = MovableClock(NOW)

    @AfterTest
    fun shutdown() = executor.shutdownNow().let { }

    private class Web(var hits: List<SourceHit>) : SearchSource {
        override val descriptor: SourceDescriptor = descriptor("tavily").copy(metered = true)
        val calls = AtomicInteger()
        var failing = false
        override fun search(query: SourceQuery): List<SourceHit> {
            calls.incrementAndGet()
            if (failing) throw RuntimeException("down")
            return hits
        }
    }

    private class MapStore : WebResultStore {
        val sets = HashMap<Triple<String, String, IntentClass>, CachedHits>()
        override fun find(sourceId: String, query: String, intentClass: IntentClass) = sets[Triple(sourceId, query, intentClass)]
        override fun replace(sourceId: String, query: String, intentClass: IntentClass, fetchedAt: Instant, hits: List<SourceHit>) {
            sets[Triple(sourceId, query, intentClass)] = CachedHits(hits, fetchedAt)
        }
    }

    /** Counts per key in memory; [down] makes every call fail like an unreachable Redis. */
    private class MapCounter : QuotaCounter {
        val counts = HashMap<Counter, Long>()
        var down = false
        override fun take(own: Counter?, ownLimit: Int, global: Counter?, globalLimit: Long): Result {
            if (down) throw IllegalStateException("down")
            val used = own?.let { counts[it] ?: 0 } ?: 0
            if (own != null && used >= ownLimit) return Result(Verdict.LIMIT_REACHED, used)
            own?.let { counts[it] = used + 1 }
            return Result(Verdict.TAKEN, used + 1)
        }
        override fun used(counter: Counter) = if (down) throw IllegalStateException("down") else counts[counter] ?: 0
        override fun delete(keys: Collection<String>) {}
    }

    /** Fails the test if a metered source touches the per-source cache. */
    private object NoCache : SearchCache {
        override fun get(key: String): CachedHits? = throw AssertionError("the search cache was read")
        override fun put(key: String, value: CachedHits, ttl: Duration) = throw AssertionError("the search cache was written")
    }

    private class Credits : SearchMetrics {
        val spent = mutableListOf<Pair<Feature?, QuotaTier?>>()
        override fun creditSpent(sourceId: String, asker: Asker?, tier: QuotaTier?) {
            spent += asker?.feature to tier
        }
    }

    private val web = Web(listOf(hit("tavily", "https://news.example/a"), hit("tavily", "https://news.example/b", rank = 1)))
    private val store = MapStore()
    private val counter = MapCounter()
    private val credits = Credits()

    private fun service(vararg others: SearchSource) = SearchResources(
        listOf(web) + others, NoCache, ResultObserver { emptyMap() }, executor, clock, metrics = credits,
        webResults = store, quota = WebSearchQuota(counter, QuotaKeys("test-secret", clock)),
    )

    private fun req(text: String = "rag frameworks", client: String = "203.0.113.7") =
        SearchRequest(text = text, asker = Asker(Feature.SEARCH, client))

    @Test
    fun `a miss calls the source live, takes one web search and stores the result set`() {
        val out = service().search(req())

        val status = out.statuses.single()
        assertEquals(ServedFrom.LIVE, status.servedFrom)
        assertEquals(1, web.calls.get())
        assertEquals(WebSearchesLeft(Feature.SEARCH, QuotaTier.GUEST, 3, 2), out.webSearches)
        assertEquals(listOf<Pair<Feature?, QuotaTier?>>(Feature.SEARCH to QuotaTier.GUEST), credits.spent)
        val stored = store.sets.values.single()
        assertEquals(NOW, stored.fetchedAt)
        assertEquals(2, stored.hits.size)
        assertEquals(NOW.plus(Duration.ofHours(48)), out.webFreshUntil)
    }

    @Test
    fun `a fresh stored set answers without a live call and without using the quota`() {
        val svc = service()
        svc.search(req())
        clock.now = NOW.plus(Duration.ofHours(47))

        val out = svc.search(req(client = "198.51.100.1"))

        val status = out.statuses.single()
        assertEquals(SourceOutcome.SUCCEEDED, status.outcome)
        assertEquals(ServedFrom.CACHE, status.servedFrom)
        assertEquals(NOW, status.cachedAt)
        assertEquals(2, out.total)
        assertEquals(1, web.calls.get())
        assertEquals(3, out.webSearches!!.remaining, "another visitor's stored answer costs nothing")
        assertEquals(1, credits.spent.size)
    }

    @Test
    fun `time-bound questions keep their results fresh for a day, general ones for two`() {
        val svc = service()
        svc.search(req("ai news"))
        svc.search(req("rag frameworks"))
        clock.now = NOW.plus(Duration.ofHours(30))

        assertEquals(ServedFrom.LIVE, svc.search(req("ai news")).statuses.single().servedFrom)
        assertEquals(ServedFrom.CACHE, svc.search(req("rag frameworks")).statuses.single().servedFrom)
        assertEquals(3, web.calls.get())
    }

    @Test
    fun `over the daily limit the source is skipped, and a stale stored set still answers`() {
        val svc = service()
        svc.search(req("rag frameworks"))
        clock.now = NOW.plus(Duration.ofDays(3))
        repeat(3) { svc.search(req("query $it")) }

        val out = svc.search(req("rag frameworks"))

        val status = out.statuses.single()
        assertEquals(SourceOutcome.SKIPPED, status.outcome)
        assertEquals(SkipReason.DAILY_LIMIT, status.skipReason)
        assertEquals(ServedFrom.STALE_CACHE, status.servedFrom)
        assertEquals(2, out.total)
        assertEquals(4, web.calls.get())
        assertEquals(0, out.webSearches!!.remaining)
        assertEquals(NOW.plus(Duration.ofHours(48)), out.webFreshUntil, "already past: a guide answer on it is not cached")
    }

    @Test
    fun `over the daily limit without a stored set the other sources still answer`() {
        val github = object : SearchSource {
            override val descriptor = descriptor("github")
            override fun search(query: SourceQuery) = listOf(hit("github", "https://github.com/a/x"))
        }
        val svc = SearchResources(listOf(web, github), object : SearchCache {
            override fun get(key: String): CachedHits? = null
            override fun put(key: String, value: CachedHits, ttl: Duration) {}
        }, ResultObserver { emptyMap() }, executor, clock, webResults = store, quota = WebSearchQuota(counter, QuotaKeys("test-secret", clock)))
        repeat(3) { svc.search(req("query $it")) }

        val out = svc.search(req("something new"))

        assertEquals(SkipReason.DAILY_LIMIT, out.statuses.single { it.source.id == "tavily" }.skipReason)
        assertEquals(ServedFrom.NONE, out.statuses.single { it.source.id == "tavily" }.servedFrom)
        assertEquals(1, out.total)
        assertNull(out.webFreshUntil)
    }

    @Test
    fun `when the quota cannot be read no live call goes out`() {
        counter.down = true

        val out = service().search(req())

        val status = out.statuses.single()
        assertEquals(SourceOutcome.SKIPPED, status.outcome)
        assertEquals(SkipReason.QUOTA_UNAVAILABLE, status.skipReason)
        assertEquals(0, web.calls.get())
        assertNull(out.webSearches)
    }

    @Test
    fun `an empty result set is stored too, so the same question costs nothing again`() {
        web.hits = emptyList()
        val svc = service()
        svc.search(req())

        assertEquals(ServedFrom.CACHE, svc.search(req()).statuses.single().servedFrom)
        assertEquals(1, web.calls.get())
    }

    @Test
    fun `a new UTC day gives a guest new web searches`() {
        val svc = service()
        repeat(3) { svc.search(req("query $it")) }
        clock.now = NOW.plus(Duration.ofDays(1))

        assertEquals(2, svc.search(req("query 3")).webSearches!!.remaining)
    }

    @Test
    fun `a failed live call falls back to the stale stored set`() {
        val svc = service()
        svc.search(req())
        clock.now = NOW.plus(Duration.ofDays(3))
        web.failing = true

        val status = svc.search(req()).statuses.single()

        assertEquals(SourceOutcome.FAILED, status.outcome)
        assertEquals(ServedFrom.STALE_CACHE, status.servedFrom)
    }

    @Test
    fun `a stored set older than the keep period is not served`() {
        val svc = service()
        svc.search(req())
        clock.now = NOW.plus(Duration.ofDays(8))
        repeat(3) { svc.search(req("query $it")) }

        assertEquals(ServedFrom.NONE, svc.search(req()).statuses.single().servedFrom)
    }

    @Test
    fun `internal callers have no quota but are still served store first`() {
        val svc = service()
        svc.search(SearchRequest("rag frameworks"))
        val out = svc.search(SearchRequest("rag frameworks"))

        assertEquals(ServedFrom.CACHE, out.statuses.single().servedFrom)
        assertNull(out.webSearches)
        assertEquals(listOf<Pair<Feature?, QuotaTier?>>(null to null), credits.spent)
    }

    @Test
    fun `what is left is reported even when no live call was needed`() {
        val svc = service()
        svc.search(req())

        assertEquals(2, svc.webSearchesLeft(Asker(Feature.SEARCH, "203.0.113.7"))!!.remaining)
        assertTrue(svc.webSearchesLeft(Asker(Feature.GUIDE, "203.0.113.7"))!!.remaining == 3)
    }
}
