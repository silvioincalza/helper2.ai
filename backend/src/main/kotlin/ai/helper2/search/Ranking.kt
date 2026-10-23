package ai.helper2.search

import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import kotlin.math.ln
import kotlin.math.min

/**
 * Deterministic relevance: reciprocal-rank fusion of each source's own ranking, plus small,
 * explainable boosts.
 *
 * Popularity is a bounded tie-break, never relevance on its own: at most [POPULARITY_WEIGHT], about
 * half the fusion score of a first place in one source, so a popular result that matches poorly
 * stays below a good match. Each metric is log-scaled against its own reference and the strongest
 * one counts; metrics are never added together (the card still shows each one separately).
 * Measured reason (evaluation 2026-10-26): for "MCP server Postgres", repositories with 0-24 stars
 * filled the top places while crystaldba/postgres-mcp (3.4k stars) was not in the first 50.
 */
class Ranking(private val k: Int = 60) {

    /**
     * [titleTerms]: the words a title must contain for the term boost, without type words (see
     * [AnalyzedQuery.requiredVariants]). Default: every query word. With "server" in it, titles like
     * "x/mcp-postgres-server" got +0.02 that "crystaldba/postgres-mcp" could not get.
     */
    fun rank(results: List<MergedResult>, query: String, path: AudiencePath?,
             typeHints: Set<ResourceType> = emptySet(), titleTerms: List<String>? = null): List<MergedResult> {
        val q = normalize(query)
        val terms = (titleTerms?.map(::normalize) ?: q.split(' ')).filter { it.length > 1 }
        return results
            .map { it.copy(score = score(it, q, terms, path, typeHints)) }
            .sortedWith(compareByDescending<MergedResult> { it.score }.thenBy { it.title.lowercase() }.thenBy { it.id.value })
    }

    private fun score(r: MergedResult, q: String, terms: List<String>, path: AudiencePath?, typeHints: Set<ResourceType>): Double {
        var s = r.hits.distinctBy { it.sourceId }.sumOf { 1.0 / (k + it.rank + 1) }
        val title = normalize(r.title)
        if (title == q) s += 0.05
        else if (terms.isNotEmpty() && terms.all { title.contains(it) }) s += 0.02
        if (path != null) {
            if (r.audience.matches(path)) s += 0.01
            if (path == AudiencePath.USE_AI && r.type == ResourceType.PRODUCT) s += 0.005
            if (path == AudiencePath.BUILD_WITH_AI && r.type in BUILDER_TYPES) s += 0.005
        }
        // The query named a type ("MCP server", "library"): same size as the path boost.
        if (r.type in typeHints) s += 0.005
        s += POPULARITY_WEIGHT * popularity(r.popularity)
        return s
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    internal companion object {
        const val POPULARITY_WEIGHT = 0.008

        /** Value at which a metric counts fully. Chosen so the well-known projects of each kind reach ~1. */
        private val REFERENCE = mapOf(
            PopularityKind.GITHUB_STARS to 50_000.0,
            PopularityKind.HF_LIKES to 5_000.0,
            PopularityKind.HF_DOWNLOADS to 5_000_000.0,
            PopularityKind.NPM_DOWNLOADS_WEEKLY to 1_000_000.0,
        )

        /** 0..1: log(1 + value) / log(1 + reference), strongest metric only. */
        fun popularity(metrics: List<PopularityMetric>): Double = metrics.maxOfOrNull { m ->
            val ref = REFERENCE[m.kind] ?: return@maxOfOrNull 0.0
            if (m.value <= 0) 0.0 else min(1.0, ln(1.0 + m.value) / ln(1.0 + ref))
        } ?: 0.0

        val BUILDER_TYPES = setOf(
            ResourceType.MODEL, ResourceType.MCP_SERVER, ResourceType.LIBRARY, ResourceType.SKILL,
            ResourceType.AGENT, ResourceType.PROMPT, ResourceType.DATASET,
        )
    }
}
