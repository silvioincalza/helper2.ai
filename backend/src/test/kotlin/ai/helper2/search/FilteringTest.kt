package ai.helper2.search

import ai.helper2.shared.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals

class FilteringTest {

    private val merger = ResultMerger()

    private val results = merger.merge(
        listOf(
            hit("github", "https://github.com/a/mit", license = "MIT", type = ResourceType.MCP_SERVER,
                published = "2024-01-01T00:00:00Z", updated = "2026-05-01T00:00:00Z"),
            hit("github", "https://github.com/a/apache", license = "Apache-2.0", type = ResourceType.LIBRARY,
                published = "2025-01-01T00:00:00Z", updated = "2026-01-01T00:00:00Z"),
            hit("github", "https://github.com/a/nolicense", type = ResourceType.LIBRARY),
            hit("github", "https://github.com/a/conflict", license = "MIT", published = "2023-01-01T00:00:00Z"),
            hit("npm", "https://github.com/a/conflict", license = "ISC"),
        ),
        contexts("github", "npm"),
    )

    private fun titles(list: List<MergedResult>) = list.map { it.title }.toSet()

    @Test
    fun `no filter keeps everything`() {
        assertEquals(4, Filtering.apply(results, SearchFilters()).size)
    }

    @Test
    fun `selecting a value excludes unknowns`() {
        val out = Filtering.apply(results, SearchFilters(licenses = setOf("MIT")))
        assertEquals(setOf("mit", "conflict"), titles(out))
    }

    @Test
    fun `licence values match whatever their case`() {
        val out = Filtering.apply(results, SearchFilters(licenses = setOf("mit", "APACHE-2.0")))
        assertEquals(setOf("mit", "apache", "conflict"), titles(out))
    }

    @Test
    fun `unknown bucket selects resources where no source said`() {
        val out = Filtering.apply(results, SearchFilters(licenses = setOf(UNKNOWN)))
        assertEquals(setOf("nolicense"), titles(out))
    }

    @Test
    fun `values within a facet are ORed and facets are ANDed`() {
        val out = Filtering.apply(
            results,
            SearchFilters(licenses = setOf("MIT", "Apache-2.0"), types = setOf(ResourceType.LIBRARY)),
        )
        assertEquals(setOf("apache", "conflict"), titles(out))
    }

    @Test
    fun `updated after requires a known date`() {
        val out = Filtering.apply(results, SearchFilters(updatedAfter = java.time.LocalDate.parse("2026-03-01")))
        assertEquals(setOf("mit"), titles(out))
    }

    @Test
    fun `facets are disjunctive and include an unknown bucket`() {
        val facets = Facets.compute(results, SearchFilters(licenses = setOf("MIT")))
        val license = facets.getValue("license").associate { it.value to it.count }
        // License facet ignores its own selection so users can widen it.
        assertEquals(2, license["MIT"])
        assertEquals(1, license["Apache-2.0"])
        assertEquals(1, license[UNKNOWN])
        // Type facet respects the license selection.
        val type = facets.getValue("type").associate { it.value to it.count }
        assertEquals(1, type["MCP_SERVER"])
        assertEquals(1, type["LIBRARY"])
    }

    @Test
    fun `newest sorts by publication with unknown dates last`() {
        val sorted = Sorting.sort(results, SortOrder.NEWEST)
        assertEquals(listOf("apache", "mit", "conflict", "nolicense"), sorted.map { it.title })
    }

    @Test
    fun `recently updated sorts by last update with unknown last`() {
        val sorted = Sorting.sort(results, SortOrder.RECENTLY_UPDATED)
        assertEquals("mit", sorted.first().title)
    }
}
