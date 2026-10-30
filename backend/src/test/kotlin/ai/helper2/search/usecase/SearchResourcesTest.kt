package ai.helper2.search.usecase

import ai.helper2.platform.resilience.Resilience
import ai.helper2.platform.resilience.Resilience4jPolicies
import ai.helper2.search.CachedHits
import ai.helper2.search.Freshness
import ai.helper2.search.MergedResult
import ai.helper2.search.NOW
import ai.helper2.search.NoticeCode
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchFilters
import ai.helper2.search.SearchRequest
import ai.helper2.search.SearchSource
import ai.helper2.search.ServedFrom
import ai.helper2.search.SkipReason
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceFailure
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceOutcome
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.shared.ResourceId
import ai.helper2.testing.MovableClock
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SearchResourcesTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = MovableClock(NOW)

    @AfterTest
    fun shutdown() = executor.shutdownNow().let { }

    private class FakeSource(
        override val descriptor: SourceDescriptor,
        val behaviour: (SourceQuery) -> List<SourceHit>,
    ) : SearchSource {
        val calls = AtomicInteger()
        override fun search(query: SourceQuery): List<SourceHit> {
            calls.incrementAndGet()
            return behaviour(query)
        }
    }

    private class MapCache : SearchCache {
        val map = ConcurrentHashMap<String, CachedHits>()
        var broken = false
        override fun get(key: String): CachedHits? = if (broken) throw IllegalStateException("down") else map[key]
        override fun put(key: String, value: CachedHits, ttl: Duration) {
            if (broken) throw IllegalStateException("down")
            map[key] = value
        }
    }

    private fun ok(id: String, vararg urls: String) =
        FakeSource(descriptor(id)) { urls.mapIndexed { i, u -> hit(id, u, rank = i) } }

    private fun service(
        sources: List<SearchSource>,
        cache: SearchCache = MapCache(),
        observer: ResultObserver = ResultObserver { emptyMap() },
        resilience: Resilience = Resilience.NONE,
    ) = SearchResources(sources, cache, observer, executor, clock, staleFor = Duration.ofHours(24), resilience = resilience)

    /** Policies for [ids]: a circuit that opens after 2 failed calls of 4 for 30 s, and a bulkhead of [concurrent] calls. */
    private fun policies(vararg ids: String, concurrent: Int = 10): Resilience {
        val circuits = CircuitBreakerRegistry.ofDefaults()
        val bulkheads = BulkheadRegistry.ofDefaults()
        ids.forEach {
            circuits.circuitBreaker("source-$it", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(2).failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(1)
                .ignoreExceptions(SourceFailure.RateLimited::class.java).clock(clock).build())
            bulkheads.bulkhead("source-$it", BulkheadConfig.custom().maxConcurrentCalls(concurrent).maxWaitDuration(Duration.ZERO).build())
        }
        return Resilience4jPolicies(circuits, bulkheads, RetryRegistry.ofDefaults())
    }

    private fun req(text: String = "agent tools") = SearchRequest(text = text)

    @Test
    fun `aggregates results from every responding source`() {
        val out = service(listOf(ok("github", "https://github.com/a/x"), ok("hf", "https://huggingface.co/m/y"))).search(req())
        assertEquals(2, out.total)
        assertTrue(out.statuses.all { it.outcome == SourceOutcome.SUCCEEDED })
        assertFalse(out.coverage.partial)
    }

    @Test
    fun `slow source times out without blocking the others`() {
        val slow = FakeSource(descriptor("slow", timeout = Duration.ofMillis(100))) { Thread.sleep(2_000); emptyList() }
        val started = System.nanoTime()
        val out = service(listOf(slow, ok("github", "https://github.com/a/x"))).search(req())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 1_000, "took $elapsedMs ms")
        assertEquals(SourceOutcome.TIMED_OUT, out.statuses.single { it.source.id == "slow" }.outcome)
        assertEquals(1, out.total)
        assertTrue(out.coverage.partial)
    }

    @Test
    fun `failing source is reported as failed with a safe message`() {
        val broken = FakeSource(descriptor("broken")) { throw RuntimeException("secret token=abc leaked in stacktrace") }
        val out = service(listOf(broken, ok("github", "https://github.com/a/x"))).search(req())
        val status = out.statuses.single { it.source.id == "broken" }
        assertEquals(SourceOutcome.FAILED, status.outcome)
        assertFalse(status.message!!.contains("token"))
        assertEquals(1, out.total)
    }

    @Test
    fun `rate limited source is reported and then cooled down instead of hammered`() {
        val limited = FakeSource(descriptor("github")) { throw SourceFailure.RateLimited(Duration.ofSeconds(60)) }
        val svc = service(listOf(limited))
        assertEquals(SourceOutcome.RATE_LIMITED, svc.search(req()).statuses.single().outcome)
        val second = svc.search(req("other query")).statuses.single()
        assertEquals(SourceOutcome.SKIPPED, second.outcome)
        assertEquals(1, limited.calls.get())
        clock.now = clock.now.plusSeconds(61)
        svc.search(req("third"))
        assertEquals(2, limited.calls.get())
    }

    @Test
    fun `disabled sources are listed as skipped`() {
        val off = FakeSource(descriptor("off", enabled = false)) { error("must not be called") }
        val out = service(listOf(off, ok("github", "https://github.com/a/x"))).search(req())
        val status = out.statuses.single { it.source.id == "off" }
        assertEquals(SourceOutcome.SKIPPED, status.outcome)
        assertEquals(0, off.calls.get())
        assertFalse(out.coverage.partial, "disabled-by-config is disclosed, not partial")
    }

    @Test
    fun `repeated search within ttl is served from cache`() {
        val gh = ok("github", "https://github.com/a/x")
        val svc = service(listOf(gh))
        svc.search(req())
        clock.now = clock.now.plusSeconds(30)
        val out = svc.search(req("  AGENT   tools "))
        assertEquals(1, gh.calls.get())
        val status = out.statuses.single()
        assertEquals(ServedFrom.CACHE, status.servedFrom)
        assertEquals(NOW, status.cachedAt)
        assertEquals(Freshness.CACHED, out.results.single().freshness)
    }

    @Test
    fun `after ttl the source is queried live again`() {
        val gh = ok("github", "https://github.com/a/x")
        val svc = service(listOf(gh))
        svc.search(req())
        clock.now = clock.now.plus(Duration.ofMinutes(11))
        svc.search(req())
        assertEquals(2, gh.calls.get())
    }

    @Test
    fun `when live fails a stale cache entry is served and labelled stale`() {
        var fail = false
        val gh = FakeSource(descriptor("github")) {
            if (fail) throw SourceFailure.Unavailable("503") else listOf(hit("github", "https://github.com/a/x"))
        }
        val svc = service(listOf(gh))
        svc.search(req())
        fail = true
        clock.now = clock.now.plus(Duration.ofHours(2))
        val out = svc.search(req())
        val status = out.statuses.single()
        assertEquals(SourceOutcome.FAILED, status.outcome)
        assertEquals(ServedFrom.STALE_CACHE, status.servedFrom)
        assertEquals(1, out.total)
        assertEquals(Freshness.STALE, out.results.single().freshness)
        assertTrue(out.coverage.partial)
    }

    @Test
    fun `stale entries beyond the stale window are not served`() {
        var fail = false
        val gh = FakeSource(descriptor("github")) {
            if (fail) throw SourceFailure.Unavailable("503") else listOf(hit("github", "https://github.com/a/x"))
        }
        val svc = service(listOf(gh))
        svc.search(req())
        fail = true
        clock.now = clock.now.plus(Duration.ofHours(25))
        val out = svc.search(req())
        assertEquals(ServedFrom.NONE, out.statuses.single().servedFrom)
        assertEquals(0, out.total)
    }

    @Test
    fun `cache outage degrades to live search with a notice`() {
        val cache = MapCache().apply { broken = true }
        val out = service(listOf(ok("github", "https://github.com/a/x")), cache).search(req())
        assertEquals(1, out.total)
        assertEquals(ServedFrom.LIVE, out.statuses.single().servedFrom)
        assertTrue(out.notices.any { it.code == NoticeCode.CACHE_UNAVAILABLE })
    }

    @Test
    fun `non live sources are never cached`() {
        val idx = FakeSource(descriptor("index", mode = ai.helper2.shared.SourceMode.INDEXED)) {
            listOf(hit("index", "https://github.com/a/x"))
        }
        val cache = MapCache()
        val svc = service(listOf(idx), cache)
        svc.search(req()); svc.search(req())
        assertEquals(2, idx.calls.get())
        assertTrue(cache.map.isEmpty())
    }

    @Test
    fun `discovered date comes from the observer and its outage is disclosed`() {
        val firstSeen = Instant.parse("2026-01-05T00:00:00Z")
        val ok = service(listOf(ok("github", "https://github.com/a/x")), observer = ResultObserver { r -> r.associate { it.id to firstSeen } })
            .search(req())
        assertEquals(firstSeen, ok.results.single().discovered.value)

        val down = service(listOf(ok("github", "https://github.com/a/x")), observer = ResultObserver { error("db down") }).search(req())
        assertEquals(null, down.results.single().discovered.value)
        assertTrue(down.notices.any { it.code == NoticeCode.INDEX_UNAVAILABLE })
    }

    @Test
    fun `only hits fetched live are handed over for recording, never cache replays`() {
        val recorded = mutableListOf<List<MergedResult>>()
        val observer = object : ResultObserver {
            override fun firstDiscovered(results: List<MergedResult>) = emptyMap<ResourceId, Instant>()
            override fun record(results: List<MergedResult>) {
                recorded += results
            }
        }
        val cached = ok("github", "https://github.com/a/x")
        val index = FakeSource(descriptor("index", mode = ai.helper2.shared.SourceMode.INDEXED)) {
            listOf(hit("index", "https://github.com/a/x"))
        }
        val svc = service(listOf(cached, index), observer = observer)
        svc.search(req())
        assertEquals(setOf("github", "index"), recorded.single().single().hits.map { it.sourceId }.toSet())

        recorded.clear()
        svc.search(req())
        assertEquals(listOf("index"), recorded.single().single().hits.map { it.sourceId }, "the github hit came from the cache")
    }

    @Test
    fun `all sources failing yields an honest empty response`() {
        val bad = FakeSource(descriptor("a")) { throw SourceFailure.Unavailable("x") }
        val out = service(listOf(bad)).search(req())
        assertEquals(0, out.total)
        assertTrue(out.notices.any { it.code == NoticeCode.ALL_SOURCES_FAILED })
        assertEquals(0, out.coverage.sourcesSucceeded)
    }

    @Test
    fun `italian query is expanded for sources and disclosed`() {
        var seen: SourceQuery? = null
        val src = FakeSource(descriptor("github")) { q -> seen = q; emptyList() }
        val out = service(listOf(src)).search(req("app per tradurre"))
        assertEquals(listOf("translation"), seen!!.expandedTerms)
        assertTrue(out.notices.any { it.code == NoticeCode.QUERY_EXPANDED })
        assertEquals(listOf("translation"), out.interpretation.expandedTerms)
    }

    @Test
    fun `filters sort and pagination apply after merging`() {
        val gh = FakeSource(descriptor("github")) {
            (0 until 25).map { hit("github", "https://github.com/a/r$it", rank = it, license = if (it % 2 == 0) "MIT" else null) }
        }
        val out = service(listOf(gh)).search(SearchRequest(text = "x", filters = SearchFilters(licenses = setOf("MIT")), page = 1, size = 10))
        assertEquals(13, out.total)
        assertEquals(3, out.results.size)
        assertNotNull(out.facets["license"])
    }

    @Test
    fun `ids are stable resource ids`() {
        val out = service(listOf(ok("github", "https://github.com/A/X"))).search(req())
        assertEquals(ResourceId.of(ai.helper2.shared.CanonicalUrl.of("https://github.com/a/x")!!), out.results.single().id)
    }

    @Test
    fun `latency is measured per source, not by collection order`() {
        val slowFirst = FakeSource(descriptor("a-slow", timeout = Duration.ofSeconds(2), priority = 1)) { Thread.sleep(300); emptyList() }
        val fast = FakeSource(descriptor("b-fast", priority = 2)) { emptyList() }
        val out = service(listOf(slowFirst, fast)).search(req())
        val fastLatency = out.statuses.single { it.source.id == "b-fast" }.latency!!
        assertTrue(fastLatency < Duration.ofMillis(200), "fast source reported $fastLatency")
    }

    @Test
    fun `a planned web query splits the cache only for sources that use it`() {
        val web = descriptor("tavily").copy(usesWebQuery = true)
        val other = descriptor("github")
        val plain = SourceQuery("nvidia agents", emptyList(), null, 10)
        val planned = plain.copy(webQuery = "NVIDIA AI agents announcement this week")

        assertTrue(SearchResources.cacheKey(web, plain) != SearchResources.cacheKey(web, planned))
        assertFalse(SearchResources.cacheKey(web, planned).contains("announcement"))
        assertEquals(SearchResources.cacheKey(other, plain), SearchResources.cacheKey(other, planned))
    }

    @Test
    fun `an enabled source without a policy stops startup, a disabled one does not need one`() {
        val resilience = policies("github")
        service(listOf(ok("github"), FakeSource(descriptor("off", enabled = false)) { emptyList() }), resilience = resilience)

        val e = assertFailsWith<IllegalStateException> { service(listOf(ok("github"), ok("npm")), resilience = resilience) }
        assertTrue(e.message!!.contains("source-npm"))
    }

    @Test
    fun `a source that keeps failing is skipped without a call while its circuit is open, then tried again`() {
        var down = true
        val flaky = FakeSource(descriptor("npm")) { if (down) throw SourceFailure.Unavailable("HTTP 502") else listOf(hit("npm", "https://www.npmjs.com/package/x")) }
        val svc = service(listOf(flaky), resilience = policies("npm"))

        repeat(2) { assertEquals(SourceOutcome.FAILED, svc.search(req("q$it")).statuses.single().outcome) }
        val open = svc.search(req("q3")).statuses.single()
        assertEquals(SourceOutcome.SKIPPED, open.outcome)
        assertEquals(SkipReason.CIRCUIT_OPEN, open.skipReason)
        assertEquals(2, flaky.calls.get(), "no call while the circuit is open")

        down = false
        clock.now = clock.now.plusSeconds(31)
        val back = svc.search(req("q4")).statuses.single()
        assertEquals(SourceOutcome.SUCCEEDED, back.outcome)
        assertEquals(3, flaky.calls.get())
    }

    @Test
    fun `an open circuit still serves the stale cache entry`() {
        var down = false
        val gh = FakeSource(descriptor("github")) { if (down) throw SourceFailure.Unavailable("HTTP 500") else listOf(hit("github", "https://github.com/a/x")) }
        val svc = service(listOf(gh), resilience = policies("github"))
        svc.search(req())
        down = true
        clock.now = clock.now.plus(Duration.ofHours(1))
        repeat(2) { svc.search(req()) }

        val status = svc.search(req()).statuses.single()
        assertEquals(SkipReason.CIRCUIT_OPEN, status.skipReason)
        assertEquals(ServedFrom.STALE_CACHE, status.servedFrom)
        assertEquals(1, status.resultCount)
    }

    @Test
    fun `a rate limit does not count against the circuit`() {
        val limited = FakeSource(descriptor("github")) { throw SourceFailure.RateLimited(Duration.ofSeconds(1)) }
        val svc = service(listOf(limited), resilience = policies("github"))

        repeat(3) {
            assertEquals(SourceOutcome.RATE_LIMITED, svc.search(req("q$it")).statuses.single().outcome)
            clock.now = clock.now.plusSeconds(2)
        }
    }

    @Test
    fun `a source already running as many calls as its bulkhead allows is skipped as busy`() {
        val inside = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = FakeSource(descriptor("tavily", timeout = Duration.ofSeconds(5))) {
            inside.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            emptyList()
        }
        val svc = service(listOf(slow), resilience = policies("tavily", concurrent = 1))
        val first = executor.submit<Unit> { svc.search(req("first")) }
        assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val busy = svc.search(req("second")).statuses.single()
        release.countDown()
        first.get()

        assertEquals(SourceOutcome.SKIPPED, busy.outcome)
        assertEquals(SkipReason.BUSY, busy.skipReason)
        assertEquals(1, slow.calls.get())
    }

}
