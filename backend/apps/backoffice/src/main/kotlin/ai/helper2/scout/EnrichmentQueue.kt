package ai.helper2.scout

/**
 * Where a resource stands in the enrichment queue, and which admin action applies to it (ADR 0026
 * (part was 0045) §7). An action changes the order only: the daily enrichment cap still decides how
 * many resources get a model call.
 *
 * [due]: the backlog rule takes it before any admin decision (never run, model unavailable, or
 * FAILED with retries left). [outOfRetries]: its last run FAILED and its FAILED runs reached the
 * cap. [skipped] and [prioritised]: an admin's decision that still holds.
 */
data class QueuePlace(
  val due: Boolean,
  val outOfRetries: Boolean,
  val skipped: Boolean,
  val prioritised: Boolean,
) {
  /** In the backlog the Scout takes from. */
  val waiting: Boolean
    get() = due && !skipped

  /** Back into the backlog: a skipped resource, or one out of retries. */
  fun requeue(): QueueOutcome =
    if (skipped || outOfRetries) QueueOutcome.DONE else QueueOutcome.NOT_STOPPED

  /** Out of the backlog until requeued: only a resource that waits. */
  fun skip(): QueueOutcome =
    when {
      skipped -> QueueOutcome.ALREADY_SKIPPED
      !due -> QueueOutcome.NOT_WAITING
      else -> QueueOutcome.DONE
    }

  /**
   * To the top: only a resource that waits, while fewer than [MAX_PRIORITISED] are there
   * ([prioritisedNow]). The bound keeps "the top" short: with no bound the order would be whatever
   * an admin clicked last.
   */
  fun prioritise(prioritisedNow: Int): QueueOutcome =
    when {
      !waiting -> QueueOutcome.NOT_WAITING
      prioritised -> QueueOutcome.ALREADY_PRIORITISED
      prioritisedNow >= MAX_PRIORITISED -> QueueOutcome.TOP_FULL
      else -> QueueOutcome.DONE
    }

  companion object {
    /** At most this many resources wait at the top at a time. */
    const val MAX_PRIORITISED = 50
  }
}

/** What an admin's queue action did; every outcome but DONE leaves the queue as it was. */
enum class QueueOutcome {
  DONE,
  /** No resource has this id. */
  NOT_FOUND,
  /** Requeue: the resource is neither skipped nor out of retries. */
  NOT_STOPPED,
  /** Skip or prioritise: the resource does not wait in the backlog. */
  NOT_WAITING,
  ALREADY_SKIPPED,
  ALREADY_PRIORITISED,
  /** Prioritise: [QueuePlace.MAX_PRIORITISED] resources already wait at the top. */
  TOP_FULL,
}
