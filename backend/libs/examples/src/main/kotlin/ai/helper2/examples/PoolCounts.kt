package ai.helper2.examples

import ai.helper2.shared.AudiencePath

data class PoolCounts(
  val shown: Map<AudiencePath, Int>,
  val hidden: Map<CheckOutcome, Int>,
  val paused: Int,
) {
  companion object {
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
