package ai.searchavo.search.usecase

import ai.searchavo.search.AnalyzedQuery
import ai.searchavo.search.Coverage
import ai.searchavo.search.Notice
import ai.searchavo.search.NoticeCode
import ai.searchavo.search.ServedFrom
import ai.searchavo.search.SkipReason
import ai.searchavo.search.SourceOutcome
import ai.searchavo.search.SourceStatus

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

  // UC-001 BR-007: paused, failing or busy count as queried; left out on purpose not.
  private fun wasExpectedToAnswer(status: SourceStatus): Boolean =
    status.outcome != SourceOutcome.SKIPPED ||
      status.servedFrom != ServedFrom.NONE ||
      status.skipReason in UNAVAILABLE

  private fun indexUnavailableNotice(indexDown: Boolean): Notice? =
    if (!indexDown) null
    else
      Notice(
        NoticeCode.INDEX_UNAVAILABLE,
        "The searchavo.ai catalog is unavailable; discovery dates are unknown for this search.",
      )

  private fun cacheUnavailableNotice(cacheDown: Boolean): Notice? =
    if (!cacheDown) null
    else Notice(NoticeCode.CACHE_UNAVAILABLE, "Cache unavailable; all sources were queried live.")

  // UC-001 BR-021: say when English words of a named need were searched too.
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
