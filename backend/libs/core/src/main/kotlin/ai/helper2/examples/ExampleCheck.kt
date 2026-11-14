package ai.helper2.examples

import ai.helper2.shared.AudiencePath
import java.time.Instant
import java.util.UUID

data class SearchFindings(
  val rankedIds: List<UUID>,
  val total: Int,
  val degraded: Boolean,
)

fun interface ExampleSearch {
  fun search(query: String, path: AudiencePath): SearchFindings
}

object ExampleCheck {
  const val SHOWN_WITHIN = 3
  const val LOOKED_AT = 10

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
