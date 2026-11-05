package ai.helper2.examples

import ai.helper2.shared.AudiencePath

/**
 * How many examples the home page may show per path, and how many it hides and why (ADR 0049). An
 * example is shown when it is ACTIVE and its check is PASS. A paused example counts as paused,
 * whatever its check; an active one that is not PASS counts under its check (WEAK, FAIL, PENDING).
 */
data class PoolCounts(
  /** Every path, 0 when none is shown. */
  val shown: Map<AudiencePath, Int>,
  /** WEAK, FAIL and PENDING, 0 when none: active examples the check hides. */
  val hidden: Map<CheckOutcome, Int>,
  val paused: Int,
) {
  companion object {
    /** The check outcomes that hide an active example. */
    val HIDING: List<CheckOutcome> = CheckOutcome.entries.filter { it != CheckOutcome.PASS }

    fun of(examples: Collection<HomeExample>): PoolCounts {
      val active = examples.filter { it.status == ExampleStatus.ACTIVE }
      val passing = active.filter { it.check.outcome == CheckOutcome.PASS }
      return PoolCounts(
        shown = AudiencePath.entries.associateWith { path -> passing.count { it.path == path } },
        hidden = HIDING.associateWith { outcome -> active.count { it.check.outcome == outcome } },
        paused = examples.size - active.size,
      )
    }
  }
}
