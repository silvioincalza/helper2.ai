package ai.helper2.search

import ai.helper2.search.usecase.SearchResources
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The creation-date window (ADR 0032): strict filter, pushed to sources that support it, part of their cache key. */
class DateWindowTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    @AfterTest
    fun shutdown() = executor.shutdownNow().let { }

    private val merged = ResultMerger().merge(
        listOf(
            hit("s", "https://example.com/new", published = "2026-10-20T00:00:00Z"),
            hit("s", "https://example.com/old", published = "2026-01-01T00:00:00Z"),
            hit("s", "https://example.com/undated"),
        ),
        contexts("s"),
    )

    @Test
    fun `the window keeps only resources born inside it, never undated ones`() {
        val kept = Filtering.apply(merged, SearchFilters(publishedAfter = LocalDate.of(2026, 9, 22)))
        assertEquals(listOf("new"), kept.map { it.title })
        val before = Filtering.apply(merged, SearchFilters(publishedBefore = LocalDate.of(2026, 1, 1)))
        assertEquals(listOf("old"), before.map { it.title })
    }

    @Test
    fun `the window is in the cache key only of sources that filter by date`() {
        val q = SourceQuery("ai tools", emptyList(), null, 30)
        val windowed = q.copy(publishedAfter = LocalDate.of(2026, 9, 22))
        val dated = descriptor("gh").copy(filtersByDate = true)
        val undated = descriptor("npm")
        assertNotEquals(SearchResources.cacheKey(dated, q), SearchResources.cacheKey(dated, windowed))
        assertEquals(SearchResources.cacheKey(undated, q), SearchResources.cacheKey(undated, windowed))
    }

    @Test
    fun `browsing calls only sources that can browse, with the window and the types`() {
        val seen = ConcurrentLinkedQueue<Pair<String, SourceQuery>>()
        fun source(id: String, browses: Boolean) = object : SearchSource {
            override val descriptor = descriptor(id).copy(browses = browses, filtersByDate = true)
            override fun search(query: SourceQuery): List<SourceHit> {
                seen += id to query
                return listOf(hit(id, "https://$id.example/x", published = "2026-10-20T00:00:00Z"))
            }
        }
        val cache = object : SearchCache {
            override fun get(key: String) = null
            override fun put(key: String, value: CachedHits, ttl: Duration) {}
        }
        val search = SearchResources(
            listOf(source("catalog", browses = true), source("npm", browses = false)), cache, ResultObserver { emptyMap() },
            executor, Clock.fixed(NOW, ZoneOffset.UTC),
        )
        val out = search.search(
            SearchRequest("", filters = SearchFilters(publishedAfter = LocalDate.of(2026, 9, 22)), browse = true),
        )
        assertEquals(listOf("catalog"), seen.map { it.first })
        val q = seen.single().second
        assertTrue(q.browse)
        assertEquals(LocalDate.of(2026, 9, 22), q.publishedAfter)
        val npm = out.statuses.single { it.source.id == "npm" }
        assertEquals(SourceOutcome.SKIPPED, npm.outcome)
        assertEquals("No subject to search", npm.message)
    }
}
