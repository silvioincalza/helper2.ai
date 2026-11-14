package ai.helper2.examples

import java.util.UUID

fun interface ServedExamples {
  fun served(locale: String, limit: Int): List<HomeExample>
}

interface HomeExamples {
  fun all(outcome: CheckOutcome?): List<HomeExample>

  fun find(id: UUID): HomeExample?

  fun add(input: ExampleInput, origin: ExampleOrigin, model: String?, locale: String): Added

  fun change(id: UUID, input: ExampleInput, status: ExampleStatus, version: Int): Changed

  fun remove(id: UUID): Boolean

  fun recordCheck(example: HomeExample, result: CheckResult): Boolean

  sealed interface Added {
    data class Done(val example: HomeExample) : Added

    data object Duplicate : Added
  }

  sealed interface Changed {
    data class Done(val example: HomeExample) : Changed

    data object NotFound : Changed

    data class Conflict(val current: HomeExample) : Changed

    data object Duplicate : Changed
  }
}

fun interface ResourceTitles {
  fun of(ids: Collection<UUID>): Map<UUID, String>
}
