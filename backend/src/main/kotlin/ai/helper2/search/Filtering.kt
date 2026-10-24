package ai.helper2.search

import ai.helper2.shared.Audience
import ai.helper2.shared.ResourceType
import java.time.LocalDate
import java.time.ZoneOffset

/** Facet value meaning "no source said". Selectable like any other value. */
const val UNKNOWN = "unknown"

enum class SortOrder { RELEVANCE, NEWEST, RECENTLY_UPDATED }

data class SearchFilters(
    val types: Set<ResourceType> = emptySet(),
    val sources: Set<String> = emptySet(),
    val licenses: Set<String> = emptySet(),
    val openSource: Set<String> = emptySet(),
    val pricing: Set<String> = emptySet(),
    val platforms: Set<String> = emptySet(),
    val useCases: Set<String> = emptySet(),
    val techLevels: Set<String> = emptySet(),
    val audiences: Set<Audience> = emptySet(),
    val updatedAfter: LocalDate? = null,
)

/** A facet: how to read the values of a result, and which filter selection applies to it. */
internal class Facet(
    val name: String,
    val selected: (SearchFilters) -> Set<String>,
    val values: (MergedResult) -> Set<String>,
    /** Free-text values typed into a URL ("mit") must still match how sources write them ("MIT"). */
    val ignoreCase: Boolean = false,
) {
    fun matches(r: MergedResult, selection: Set<String>): Boolean {
        val own = values(r)
        return if (ignoreCase) own.any { v -> selection.any { it.equals(v, ignoreCase = true) } } else own.any { it in selection }
    }
}

internal val FACETS: List<Facet> = listOf(
    Facet("type", { f -> f.types.map { it.name }.toSet() }, { setOf(it.type.name) }),
    Facet("source", { it.sources }, { r -> r.sources.map { it.sourceId }.toSet() }),
    Facet("license", { it.licenses }, { r -> r.assertedLicenses.mapNotNull(ResultMerger::normalizeLicense).toSet().ifEmpty { setOf(UNKNOWN) } },
        ignoreCase = true),
    Facet("openSource", { it.openSource }, { r -> setOf(r.openSource?.toString() ?: UNKNOWN) }),
    Facet("pricing", { it.pricing }, { r -> setOf(r.pricing?.name ?: UNKNOWN) }),
    Facet("platform", { it.platforms }, { r -> r.platforms.ifEmpty { setOf(UNKNOWN) } }),
    Facet("useCase", { it.useCases }, { r -> r.useCases.ifEmpty { setOf(UNKNOWN) } }),
    Facet("techLevel", { it.techLevels }, { r -> setOf(r.techLevel?.name ?: UNKNOWN) }),
    Facet("audience", { f -> f.audiences.map { it.name }.toSet() }, { r ->
        when (r.audience) {
            Audience.BOTH -> setOf(Audience.BOTH.name, Audience.USE_AI.name, Audience.BUILD_WITH_AI.name)
            else -> setOf(r.audience.name)
        }
    }),
)

object Filtering {
    @JvmStatic
    fun apply(results: List<MergedResult>, filters: SearchFilters): List<MergedResult> =
        results.filter { matches(it, filters, except = null) }

    internal fun matches(r: MergedResult, filters: SearchFilters, except: String?): Boolean {
        for (facet in FACETS) {
            if (facet.name == except) continue
            val selected = facet.selected(filters)
            if (selected.isNotEmpty() && !facet.matches(r, selected)) return false
        }
        val after = filters.updatedAfter
        if (after != null) {
            val updated = r.updated.value ?: return false
            if (updated.isBefore(after.atStartOfDay(ZoneOffset.UTC).toInstant())) return false
        }
        return true
    }
}

data class FacetCount(val value: String, val count: Int)

object Facets {
    /** Disjunctive faceting: each facet's counts respect every filter except its own. */
    @JvmStatic
    fun compute(results: List<MergedResult>, filters: SearchFilters): Map<String, List<FacetCount>> =
        FACETS.associate { facet ->
            val counts = results
                .filter { Filtering.matches(it, filters, except = facet.name) }
                .flatMap { facet.values(it) }
                .groupingBy { it }
                .eachCount()
            facet.name to counts.map { FacetCount(it.key, it.value) }
                .sortedWith(compareBy<FacetCount> { it.value == UNKNOWN }.thenByDescending { it.count }.thenBy { it.value })
        }
}

object Sorting {
    /** Unknown dates always sort last; ties keep relevance order (stable sort). */
    @JvmStatic
    fun sort(results: List<MergedResult>, order: SortOrder): List<MergedResult> = when (order) {
        SortOrder.RELEVANCE -> results
        SortOrder.NEWEST -> results.sortedWith(compareBy(nullsLast(reverseOrder())) { it.published.value })
        SortOrder.RECENTLY_UPDATED -> results.sortedWith(compareBy(nullsLast(reverseOrder())) { it.updated.value })
    }
}
