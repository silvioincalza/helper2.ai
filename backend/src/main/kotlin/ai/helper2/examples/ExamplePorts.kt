package ai.helper2.examples

import java.util.UUID

/** Outbound port: the stored examples (PostgreSQL, table home_example). */
interface HomeExamples {

  /** What the home page may show for [locale]: active and passing the check, at most [limit]. */
  fun served(locale: String, limit: Int): List<HomeExample>

  /** Every example, newest change first; only those whose last check was [outcome] when given. */
  fun all(outcome: CheckOutcome?): List<HomeExample>

  fun find(id: UUID): HomeExample?

  /** Stores [input] as a new example with check PENDING. */
  fun add(input: ExampleInput, origin: ExampleOrigin, model: String?, locale: String): Added

  /**
   * Replaces the texts, path, expected results and status of [id] when it is still at [version];
   * the check goes back to PENDING when the search it checks (query, path, expected) changed.
   */
  fun change(id: UUID, input: ExampleInput, status: ExampleStatus, version: Int): Changed

  /** True when an example was removed. */
  fun remove(id: UUID): Boolean

  /**
   * Stores [result] for [example] unless its search changed since it was read (another query, path
   * or expected results): a check never lands on a search it did not run. True when stored.
   */
  fun recordCheck(example: HomeExample, result: CheckResult): Boolean

  sealed interface Added {
    data class Done(val example: HomeExample) : Added

    /** Another example of the locale has the same label or query. */
    data object Duplicate : Added
  }

  sealed interface Changed {
    data class Done(val example: HomeExample) : Changed

    data object NotFound : Changed

    /** Someone changed it first: the stored version is not the one the change read. */
    data class Conflict(val current: HomeExample) : Changed

    data object Duplicate : Changed
  }
}

/** Outbound port: the catalog's titles of resources, to check and show expected results. */
fun interface ResourceTitles {
  /** The title of each id the catalog knows; an unknown id is absent. */
  fun of(ids: Collection<UUID>): Map<UUID, String>
}
