package ai.helper2.search

import ai.helper2.shared.AudiencePath
import java.time.Instant

data class SearchRequest @JvmOverloads constructor(
    val text: String,
    val path: AudiencePath? = null,
    val filters: SearchFilters = SearchFilters(),
    val sort: SortOrder = SortOrder.RELEVANCE,
    val page: Int = 0,
    val size: Int = 20,
    /** Natural-language query for web search sources, written by the guide's planner; null outside the guide. */
    val webQuery: String? = null,
    /** No subject words to search: sources that can list by date and type do, the others are skipped. */
    val browse: Boolean = false,
    /** Who asks, for the daily web-search quota; null for internal callers, who have none. */
    val asker: Asker? = null,
    /** How fast web results for this request age; null = read from the request ([IntentClass.of]). */
    val intentClass: IntentClass? = null,
)

/** GUIDE_DAILY_QUOTA is the guide's own: the asker's daily AI answers are used up, so the answer is search only. */
/** GUIDE_SUBJECT_NOT_FOUND: the thing the question names (to compare, to find alternatives to, to date) is not in our sources. */
enum class NoticeCode { CACHE_UNAVAILABLE, INDEX_UNAVAILABLE, QUERY_EXPANDED, ALL_SOURCES_FAILED, GUIDE_DAILY_QUOTA, GUIDE_SUBJECT_NOT_FOUND }

data class Notice(val code: NoticeCode, val message: String)

data class Coverage(
    val partial: Boolean,
    val sourcesConfigured: Int,
    val sourcesQueried: Int,
    val sourcesSucceeded: Int,
)

data class SearchOutcome(
    val request: SearchRequest,
    val interpretation: AnalyzedQuery,
    val executedAt: Instant,
    val statuses: List<SourceStatus>,
    val results: List<MergedResult>,
    val total: Int,
    val facets: Map<String, List<FacetCount>>,
    val coverage: Coverage,
    val notices: List<Notice>,
    /** Web searches the asker's feature has left today; null without an asker, a metered source or a readable quota. */
    val webSearches: WebSearchesLeft? = null,
    /** When the metered web results in this outcome stop being fresh; null when it holds none. */
    val webFreshUntil: Instant? = null,
) {
    companion object {
        /** No search ran (the guide asked a question back, or compared entries it looked up one by one). */
        @JvmStatic
        fun empty(at: Instant): SearchOutcome = SearchOutcome(
            SearchRequest(""), QueryAnalyzer().analyze(""), at, emptyList(), emptyList(), 0, emptyMap(),
            Coverage(false, 0, 0, 0), emptyList(),
        )
    }
}

