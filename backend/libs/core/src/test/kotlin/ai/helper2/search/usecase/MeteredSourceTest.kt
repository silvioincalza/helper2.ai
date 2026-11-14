package ai.helper2.search.usecase

import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Result
import ai.helper2.platform.quota.QuotaCounter.Verdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.platform.resilience.CallRefused
import ai.helper2.platform.resilience.Resilience
import ai.helper2.search.Asker
import ai.helper2.search.CachedHits
import ai.helper2.search.Feature
import ai.helper2.search.NOW
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchMetrics
import ai.helper2.search.SearchRequest
import ai.helper2.search.SearchSource
import ai.helper2.search.ServedFrom
import ai.helper2.search.SkipReason
import ai.helper2.search.SortOrder
import ai.helper2.search.SourceDescriptor
import ai.helper2.search.SourceFailure
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeteredSourceTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = MovableClock(NOW)

  @AfterTest fun shutdown() = executor.shutdownNow().let {}

  private class Web(var hits: List<SourceHit>, id: String = "live-web") : SearchSource {
    override val descriptor: SourceDescriptor = descriptor(id).copy(metered = true)
    val calls = AtomicInteger()
    var failing = false
    var failure: RuntimeException = RuntimeException("down")
    var answersAfter: Duration = Duration.ZERO
    var whenCalled: (SourceQuery) -> Unit = {}

    override fun search(query: SourceQuery): List<SourceHit> {
      calls.incrementAndGet()
      whenCalled(query)
      Thread.sleep(answersAfter.toMillis())
      if (failing) throw failure
      return hits
    }
  }

  private class MapStore : WebResultStore {
    val sets = HashMap<Pair<String, String>, CachedHits>()

    override fun find(sourceId: String, query: String) = sets[sourceId to query]

    override fun replace(
      sourceId: String,
      query: String,
      fetchedAt: Instant,
      hits: List<SourceHit>,
    ) {
      sets[sourceId to query] = CachedHits(hits, fetchedAt)
    }
  }

  private class MapCounter : QuotaCounter {
    val counts = HashMap<Counter, Long>()
    var down = false

    override fun take(own: Counter?, ownLimit: Int, global: Counter?, globalLimit: Long): Result {
      if (down) throw IllegalStateException("down")
      val used = own?.let { counts[it] ?: 0 } ?: 0
      if (own != null && used >= ownLimit) return Result(Verdict.LIMIT_REACHED, used)
      global?.let { counts[it] = (counts[it] ?: 0) + 1 }
      own?.let { counts[it] = used + 1 }
      return Result(Verdict.TAKEN, used + 1)
    }

    override fun add(counter: Counter, amount: Long) {
      counts[counter] = (counts[counter] ?: 0) + amount
    }

    override fun giveBack(counter: Counter) {
      counts[counter] = maxOf(0, (counts[counter] ?: 0) - 1)
    }

    override fun used(counter: Counter) =
      if (down) throw IllegalStateException("down") else counts[counter] ?: 0
  }

  private object NoCache : SearchCache {
    override fun get(key: String): CachedHits? = throw AssertionError("the search cache was read")

    override fun put(key: String, value: CachedHits, ttl: Duration) =
      throw AssertionError("the search cache was written")
  }

  private class Credits : SearchMetrics {
    val spent = mutableListOf<Feature?>()

    override fun creditSpent(sourceId: String, asker: Asker?) {
      spent += asker?.feature
    }
  }

  private val web =
    Web(
      listOf(
        hit("live-web", "https://news.example/a"),
        hit("live-web", "https://news.example/b", rank = 1),
      )
    )
  private val store = MapStore()
  private val counter = MapCounter()
  private val credits = Credits()

  private val quota = WebSearchQuota(counter, QuotaKeys("test-secret", clock))

  private fun service(vararg others: SearchSource, resilience: Resilience = Resilience.NONE) =
    SearchResources(
      listOf(web) + others,
      NoCache,
      ResultObserver { emptyMap() },
      executor,
      clock,
      metrics = credits,
      resilience = resilience,
      webResults = store,
      quota = quota,
    )

  private fun req(text: String = "rag frameworks", client: String = "203.0.113.7") =
    SearchRequest(text = text, asker = Asker(Feature.SEARCH, client))

  private fun refusing(reason: CallRefused.Reason) =
    object : Resilience {
      override fun <T> call(policy: String, work: java.util.function.Supplier<T>): T =
        throw CallRefused(policy, reason, null)

      override fun require(policy: String) {}
    }

  private fun creditsToday() = quota.creditsToday()!!.usedToday

  @Test
  fun `a miss calls the source live, takes one web search and stores the result set`() {
    val out = service().search(req())

    val status = out.statuses.single()
    assertEquals(ServedFrom.LIVE, status.servedFrom)
    assertEquals(1, web.calls.get())
    assertEquals(WebSearchesLeft(Feature.SEARCH, 3, 2), out.webSearches)
    assertEquals(listOf<Feature?>(Feature.SEARCH), credits.spent)
    val stored = store.sets.values.single()
    assertEquals(NOW, stored.fetchedAt)
    assertEquals(2, stored.hits.size)
    assertEquals(NOW.plus(Duration.ofHours(48)), out.webFreshUntil)
  }

  @Test
  fun `a search without web asks neither the source nor its store, and is not partial`() {
    val svc = service()
    svc.search(req())

    val out = svc.search(SearchRequest(text = "rag frameworks", web = false))

    val status = out.statuses.single()
    assertEquals(SourceOutcome.SKIPPED, status.outcome)
    assertEquals(SkipReason.WEB_NOT_ASKED, status.skipReason)
    assertEquals(ServedFrom.NONE, status.servedFrom)
    assertEquals(1, web.calls.get(), "only the first search called the source")
    assertEquals(1, credits.spent.size)
    assertEquals(0, out.total)
    assertFalse(out.coverage.partial)
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
  fun `the timeline view of a fresh search spends no second web search`() {
    val svc = service()
    svc.search(req())
    clock.now = NOW.plus(Duration.ofHours(2))

    val out = svc.search(req().copy(sort = SortOrder.NEWEST))

    assertEquals(ServedFrom.CACHE, out.statuses.single().servedFrom)
    assertEquals(1, web.calls.get())
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
    assertEquals(
      NOW.plus(Duration.ofHours(48)),
      out.webFreshUntil,
      "already past: a guide answer on it is not cached",
    )
  }

  @Test
  fun `over the daily limit without a stored set the other sources still answer`() {
    val github =
      object : SearchSource {
        override val descriptor = descriptor("github")

        override fun search(query: SourceQuery) = listOf(hit("github", "https://github.com/a/x"))
      }
    val svc =
      SearchResources(
        listOf(web, github),
        object : SearchCache {
          override fun get(key: String): CachedHits? = null

          override fun put(key: String, value: CachedHits, ttl: Duration) {}
        },
        ResultObserver { emptyMap() },
        executor,
        clock,
        webResults = store,
        quota = WebSearchQuota(counter, QuotaKeys("test-secret", clock)),
      )
    repeat(3) { svc.search(req("query $it")) }

    val out = svc.search(req("something new"))

    assertEquals(
      SkipReason.DAILY_LIMIT,
      out.statuses.single { it.source.id == "live-web" }.skipReason,
    )
    assertEquals(ServedFrom.NONE, out.statuses.single { it.source.id == "live-web" }.servedFrom)
    assertEquals(1, out.total)
    assertNull(out.webFreshUntil)
    assertFalse(out.coverage.partial)
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
    assertTrue(out.coverage.partial)
    assertEquals(1, out.coverage.sourcesQueried)
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
    assertEquals(listOf<Feature?>(null), credits.spent)
  }

  @Test
  fun `what is left is reported even when no live call was needed`() {
    val svc = service()
    svc.search(req())

    assertEquals(2, svc.webSearchesLeft(Asker(Feature.SEARCH, "203.0.113.7"))!!.remaining)
    assertTrue(svc.webSearchesLeft(Asker(Feature.GUIDE, "203.0.113.7"))!!.remaining == 3)
  }

  @Test
  fun `a live call the circuit or the bulkhead refuses gives back its credit and the visitor's web search`() {
    CallRefused.Reason.entries.forEach { reason ->
      val out = service(resilience = refusing(reason)).search(req())

      assertEquals(SourceOutcome.SKIPPED, out.statuses.single().outcome)
      assertEquals(0, web.calls.get())
      assertEquals(0, creditsToday(), "no live call went out, so no credit was spent")
      assertEquals(3, out.webSearches!!.remaining)
      assertTrue(credits.spent.isEmpty())
    }
  }

  @Test
  fun `a live call that fails gives the visitor's web search back and keeps its credit`() {
    web.failing = true

    val out = service().search(req())

    assertEquals(SourceOutcome.FAILED, out.statuses.single().outcome)
    assertEquals(1, web.calls.get())
    assertEquals(3, out.webSearches!!.remaining)
    assertEquals(1, creditsToday(), "the provider was called and may have charged")
  }

  @Test
  fun `a live call slower than its deadline gives the visitor's web search back and keeps its credit`() {
    web.answersAfter = Duration.ofSeconds(30)

    val out = service().search(req())

    assertEquals(SourceOutcome.TIMED_OUT, out.statuses.single().outcome)
    assertEquals(3, out.webSearches!!.remaining)
    assertEquals(1, creditsToday(), "the provider was called and may have charged")
  }

  @Test
  fun `a source paused after a rate limit takes neither a credit nor a web search`() {
    val svc = service()
    web.failing = true
    web.failure = SourceFailure.RateLimited(Duration.ofMinutes(5))
    val limited = svc.search(req())

    val paused = svc.search(req("another question"))

    assertEquals(SourceOutcome.RATE_LIMITED, limited.statuses.single().outcome)
    assertEquals(SkipReason.RATE_LIMIT_PAUSE, paused.statuses.single().skipReason)
    assertEquals(1, web.calls.get())
    assertEquals(3, paused.webSearches!!.remaining)
    assertEquals(1, creditsToday(), "only the call that reached the provider")
  }

  @Test
  fun `a failed web search can be asked again and then takes one web search`() {
    val svc = service()
    web.failing = true
    svc.search(req())
    web.failing = false

    val out = svc.search(req())

    assertEquals(ServedFrom.LIVE, out.statuses.single().servedFrom)
    assertEquals(2, out.webSearches!!.remaining)
    assertEquals(2, creditsToday())
  }

  @Test
  fun `one action whose first live call fails takes its web search at the call that answers`() {
    val svc = service()
    val question = Asker(Feature.GUIDE, "203.0.113.7")
    web.failing = true
    svc.search(SearchRequest(text = "first", asker = question))
    web.failing = false

    val out = svc.search(SearchRequest(text = "second", asker = question))

    assertEquals(2, out.webSearches!!.remaining)
    assertEquals(2, svc.webSearchesLeft(Asker(Feature.GUIDE, "203.0.113.7"))!!.remaining)
  }

  @Test
  fun `one action that got a live answer keeps its web search when a later call fails`() {
    val svc = service()
    val question = Asker(Feature.GUIDE, "203.0.113.7")
    svc.search(SearchRequest(text = "first", asker = question))
    web.failing = true

    val out = svc.search(SearchRequest(text = "second", asker = question))

    assertEquals(SourceOutcome.FAILED, out.statuses.single().outcome)
    assertEquals(2, out.webSearches!!.remaining)
    assertEquals(2, svc.webSearchesLeft(Asker(Feature.GUIDE, "203.0.113.7"))!!.remaining)
  }

  @Test
  fun `a search one metered source answers keeps its web search when another one fails`() {
    val other = Web(listOf(hit("other-web", "https://news.example/c")), "other-web")
    web.failing = true

    val out = service(other).search(req())

    assertEquals(
      listOf(SourceOutcome.FAILED, SourceOutcome.SUCCEEDED),
      out.statuses.map { it.outcome },
    )
    assertEquals(2, out.webSearches!!.remaining)
    assertEquals(2, creditsToday())
  }

  @Test
  fun `one action searching twice at once keeps its web search when the call that fails comes back first`() {
    val svc = service()
    val question = Asker(Feature.GUIDE, "203.0.113.7")
    val answeringStarted = CountDownLatch(1)
    val failedCameBack = CountDownLatch(1)
    web.whenCalled = { query ->
      if (query.text == "fails") throw RuntimeException("down")
      answeringStarted.countDown()
      failedCameBack.await()
    }
    val answering =
      executor.submit<Int> {
        svc.search(SearchRequest(text = "answers", asker = question)).webSearches!!.remaining
      }
    answeringStarted.await()

    val failed = svc.search(SearchRequest(text = "fails", asker = question))
    failedCameBack.countDown()

    assertEquals(SourceOutcome.FAILED, failed.statuses.single().outcome)
    assertEquals(2, answering.get())
    assertEquals(2, svc.webSearchesLeft(Asker(Feature.GUIDE, "203.0.113.7"))!!.remaining)
  }
}
