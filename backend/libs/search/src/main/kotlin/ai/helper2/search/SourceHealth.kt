package ai.helper2.search

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class SourceHealth : SearchMetrics {
  data class Entry(val outcome: SourceOutcome, val checkedAt: Instant)

  private val last = ConcurrentHashMap<String, Entry>()
  private val durations = ConcurrentHashMap<String, ArrayDeque<Duration>>()

  override fun sourceCompleted(status: SourceStatus) {
    if (status.skipReason == SkipReason.DISABLED || status.skipReason == SkipReason.WEB_NOT_ASKED)
      return
    last[status.source.id] = Entry(status.outcome, status.checkedAt)
    val latency = status.latency
    if (
      latency != null &&
        status.outcome != SourceOutcome.SKIPPED &&
        status.servedFrom != ServedFrom.CACHE
    ) {
      val recent = durations.computeIfAbsent(status.source.id) { ArrayDeque(WINDOW) }
      synchronized(recent) {
        if (recent.size == WINDOW) recent.removeFirst()
        recent.addLast(latency)
      }
    }
  }

  fun lastFor(sourceId: String, recorded: Entry?): Entry? {
    val seen = last[sourceId] ?: return recorded
    return if (recorded != null && recorded.checkedAt.isAfter(seen.checkedAt)) recorded else seen
  }

  fun responseTime(sourceId: String): Duration? {
    val recent = durations[sourceId] ?: return null
    val sorted = synchronized(recent) { recent.sorted() }
    return sorted.getOrNull((sorted.size - 1) / 2)
  }

  private companion object {
    const val WINDOW = 20
  }
}
