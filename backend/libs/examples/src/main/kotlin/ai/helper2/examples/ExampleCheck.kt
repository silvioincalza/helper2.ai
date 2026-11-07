package ai.helper2.examples

import ai.helper2.shared.AudiencePath
import java.time.Instant
import java.util.UUID

/** What the example's own search found: the ranked result ids and whether every source answered. */
data class SearchFindings(
  /** Result ids, best first: at least the first [ExampleCheck.LOOKED_AT]. */
  val rankedIds: List<UUID>,
  val total: Int,
  /** A source we asked did not answer, or the index was down: the ranking is not the usual one. */
  val degraded: Boolean,
)

/** Outbound port: the search a visitor gets when they click the example, without web search. */
fun interface ExampleSearch {
  fun search(query: String, path: AudiencePath): SearchFindings
}

/**
 * The quality check (ADR 0015): an example is a promise that its click gives a right answer at
 * once. It is shown only when an expected resource is in the top [SHOWN_WITHIN].
 */
object ExampleCheck {
  const val SHOWN_WITHIN = 3
  const val LOOKED_AT = 10

  /**
   * The result of checking [expected] against [findings]; null for a degraded search (keep the
   * last).
   */
  fun of(findings: SearchFindings, expected: Collection<UUID>, at: Instant): CheckResult? {
    if (findings.degraded) return null
    val rank =
      findings.rankedIds.take(LOOKED_AT).indexOfFirst { it in expected }.takeIf { it >= 0 }?.plus(1)
    val outcome =
      when {
        rank == null -> CheckOutcome.FAIL
        rank <= SHOWN_WITHIN -> CheckOutcome.PASS
        else -> CheckOutcome.WEAK
      }
    return CheckResult(outcome, rank, findings.total, at)
  }
}
