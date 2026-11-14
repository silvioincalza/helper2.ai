package ai.helper2.search.usecase

import ai.helper2.search.AnalyzedQuery
import ai.helper2.search.Coverage
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.ServedFrom
import ai.helper2.search.SkipReason
import ai.helper2.search.SourceOutcome
import ai.helper2.search.SourceStatus

internal object SearchNotices {
  private val UNAVAILABLE =
    setOf(
      SkipReason.RATE_LIMIT_PAUSE,
      SkipReason.CIRCUIT_OPEN,
      SkipReason.BUSY,
      SkipReason.QUOTA_UNAVAILABLE,
    )

  fun coverageOf(statuses: List<SourceStatus>, sourceCount: Int): Coverage {
    val queried = statuses.count(::wasExpectedToAnswer)
    val succeeded = statuses.count { it.outcome == SourceOutcome.SUCCEEDED }
    return Coverage(succeeded < queried, sourceCount, queried, succeeded)
  }

  fun of(
    indexDown: Boolean,
    cacheDown: Boolean,
    analyzed: AnalyzedQuery,
    statuses: List<SourceStatus>,
  ): List<Notice> =
    listOfNotNull(
      indexUnavailableNotice(indexDown),
      cacheUnavailableNotice(cacheDown),
      queryExpandedNotice(analyzed),
      allSourcesFailedNotice(statuses),
    )

  private fun wasExpectedToAnswer(status: SourceStatus): Boolean =
    status.outcome != SourceOutcome.SKIPPED ||
      status.servedFrom != ServedFrom.NONE ||
      status.skipReason in UNAVAILABLE

  private fun indexUnavailableNotice(indexDown: Boolean): Notice? =
    if (!indexDown) null
    else
      Notice(
        NoticeCode.INDEX_UNAVAILABLE,
        "The helper2.ai catalog is unavailable; discovery dates are unknown for this search.",
      )

  private fun cacheUnavailableNotice(cacheDown: Boolean): Notice? =
    if (!cacheDown) null
    else Notice(NoticeCode.CACHE_UNAVAILABLE, "Cache unavailable; all sources were queried live.")

  private fun queryExpandedNotice(analyzed: AnalyzedQuery): Notice? =
    if (analyzed.lexiconTerms.isEmpty()) null
    else
      Notice(
        NoticeCode.QUERY_EXPANDED,
        "Also searched for: ${analyzed.lexiconTerms.joinToString(", ")}",
      )

  private fun allSourcesFailedNotice(statuses: List<SourceStatus>): Notice? {
    val enabled = statuses.filter { it.source.enabled }
    if (enabled.isEmpty()) return null
    if (statuses.any { it.outcome == SourceOutcome.SUCCEEDED }) return null
    if (enabled.any { it.servedFrom == ServedFrom.STALE_CACHE }) return null
    return Notice(NoticeCode.ALL_SOURCES_FAILED, "No source could be reached.")
  }
}
