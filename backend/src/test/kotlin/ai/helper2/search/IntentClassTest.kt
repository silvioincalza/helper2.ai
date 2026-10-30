package ai.helper2.search

import ai.helper2.shared.ResourceType
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class IntentClassTest {

    private val analyzer = QueryAnalyzer()

    private fun classOf(request: SearchRequest) = IntentClass.of(request, analyzer.analyze(request.text))

    @Test
    fun `a plain search is general`() {
        assertEquals(IntentClass.GENERAL, classOf(SearchRequest("rag frameworks")))
    }

    @Test
    fun `news, courses, hackathons, conferences and events are time-bound`() {
        listOf("ai news", "llm course", "ai hackathons 2026", "agents conference", "mcp events", "corsi di machine learning")
            .forEach { assertEquals(IntentClass.TIME_BOUND, classOf(SearchRequest(it)), it) }
    }

    @Test
    fun `a filter on time-bound types, a date window or newest first is time-bound`() {
        assertEquals(IntentClass.TIME_BOUND, classOf(SearchRequest("agents", filters = SearchFilters(types = setOf(ResourceType.NEWS, ResourceType.EVENT)))))
        assertEquals(IntentClass.TIME_BOUND, classOf(SearchRequest("agents", filters = SearchFilters(publishedAfter = LocalDate.of(2026, 10, 1)))))
        assertEquals(IntentClass.TIME_BOUND, classOf(SearchRequest("agents", sort = SortOrder.NEWEST)))
        assertEquals(IntentClass.GENERAL, classOf(SearchRequest("agents", filters = SearchFilters(types = setOf(ResourceType.NEWS, ResourceType.LIBRARY)))))
    }

    @Test
    fun `the class the caller states wins`() {
        assertEquals(IntentClass.GENERAL, classOf(SearchRequest("ai news", intentClass = IntentClass.GENERAL)))
        assertEquals(IntentClass.TIME_BOUND, classOf(SearchRequest("rag", intentClass = IntentClass.TIME_BOUND)))
    }

    @Test
    fun `time-bound results are fresh for a day, general ones for two`() {
        val f = WebFreshness()
        assertEquals(Duration.ofHours(24), f.of(IntentClass.TIME_BOUND))
        assertEquals(Duration.ofHours(48), f.of(IntentClass.GENERAL))
        assertEquals(Duration.ofDays(7), f.keep)
    }
}
