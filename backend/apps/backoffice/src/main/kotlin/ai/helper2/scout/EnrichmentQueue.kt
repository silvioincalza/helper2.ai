package ai.helper2.scout

data class QueuePlace(
  val due: Boolean,
  val outOfRetries: Boolean,
  val skipped: Boolean,
  val prioritised: Boolean,
) {
  val waiting: Boolean
    get() = due && !skipped

  fun requeue(): QueueOutcome =
    if (skipped || outOfRetries) QueueOutcome.DONE else QueueOutcome.NOT_STOPPED

  fun skip(): QueueOutcome =
    when {
      skipped -> QueueOutcome.ALREADY_SKIPPED
      !due -> QueueOutcome.NOT_WAITING
      else -> QueueOutcome.DONE
    }

  fun prioritise(prioritisedNow: Int): QueueOutcome =
    when {
      !waiting -> QueueOutcome.NOT_WAITING
      prioritised -> QueueOutcome.ALREADY_PRIORITISED
      prioritisedNow >= MAX_PRIORITISED -> QueueOutcome.TOP_FULL
      else -> QueueOutcome.DONE
    }

  companion object {
    const val MAX_PRIORITISED = 50
  }
}

enum class QueueOutcome {
  DONE,
  NOT_FOUND,
  NOT_STOPPED,
  NOT_WAITING,
  ALREADY_SKIPPED,
  ALREADY_PRIORITISED,
  TOP_FULL,
}
