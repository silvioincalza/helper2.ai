package ai.helper2.search

import ai.helper2.shared.AudiencePath
import ai.helper2.shared.DateFact
import ai.helper2.shared.LanguageDetector
import ai.helper2.shared.SourceMode
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

data class SearchRequest @JvmOverloads constructor(
    val text: String,
    val path: AudiencePath? = null,
    val filters: SearchFilters = SearchFilters(),
    val sort: SortOrder = SortOrder.RELEVANCE,
    val page: Int = 0,
    val size: Int = 20,
    /** Natural-language query for web search sources, written by the guide's planner; null outside the guide. */
    val webQuery: String? = null,
)

/** GUIDE_DAILY_QUOTA is the guide's own: the asker's daily AI answers are used up, so the answer is search only. */
enum class NoticeCode { CACHE_UNAVAILABLE, INDEX_UNAVAILABLE, QUERY_EXPANDED, ALL_SOURCES_FAILED, GUIDE_DAILY_QUOTA }

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
)

