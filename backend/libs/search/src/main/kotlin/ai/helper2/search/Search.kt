package ai.helper2.search

import ai.helper2.shared.AudiencePath
import java.time.Instant

data class SearchRequest
@JvmOverloads
constructor(
  val text: String,
  val path: AudiencePath? = null,
  val filters: SearchFilters = SearchFilters(),
  val sort: SortOrder = SortOrder.RELEVANCE,
  val page: Int = 0,
  val size: Int = 20,
  val webQuery: String? = null,
  val browse: Boolean = false,
  val asker: Asker? = null,
  val intentClass: IntentClass? = null,
  val web: Boolean = true,
)

enum class NoticeCode {
  CACHE_UNAVAILABLE,
  INDEX_UNAVAILABLE,
  QUERY_EXPANDED,
  ALL_SOURCES_FAILED,
  GUIDE_DAILY_QUOTA,
  GUIDE_NETWORK_QUOTA,
  GUIDE_SUBJECT_NOT_FOUND,
  GUIDE_OUT_OF_TIME,
  GUIDE_BUDGET_SPENT,
}

data class Notice
@JvmOverloads
constructor(
  val code: NoticeCode,
  val message: String,
  val subject: String? = null,
  val query: String? = null,
  val retryAt: Instant? = null,
)

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
  val webSearches: WebSearchesLeft? = null,
  val webFreshUntil: Instant? = null,
) {
  companion object {
    @JvmStatic
    fun empty(at: Instant): SearchOutcome =
      SearchOutcome(
        SearchRequest(""),
        QueryAnalyzer().analyze(""),
        at,
        emptyList(),
        emptyList(),
        0,
        emptyMap(),
        Coverage(false, 0, 0, 0),
        emptyList(),
      )
  }
}
