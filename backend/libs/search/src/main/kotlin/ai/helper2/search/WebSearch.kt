package ai.helper2.search

import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Verdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.shared.ResourceType
import java.time.Duration
import java.util.function.Supplier

enum class Feature {
  SEARCH,
  GUIDE,
}

data class WebSearchesLeft(val feature: Feature, val limit: Int, val remaining: Int)

data class WebCredits(val usedToday: Long, val dailyCap: Long?)

class Asker(val feature: Feature, val client: String) {
  internal var decision: QuotaDecision? = null
  internal var holdsWebSearch = false
  internal var callsNotSettled = 0
  internal var answeredByTheWeb = false
}

sealed interface QuotaDecision {
  data class Allowed(val left: WebSearchesLeft?) : QuotaDecision

  data class OverLimit(val left: WebSearchesLeft?) : QuotaDecision

  data object Unavailable : QuotaDecision
}

data class DailyLimits(val search: Int = 3, val guide: Int = 3) {
  fun of(feature: Feature): Int = if (feature == Feature.SEARCH) search else guide
}

enum class IntentClass {
  TIME_BOUND,
  GENERAL;

  companion object {
    private val NON_WORD = Regex("[^\\p{L}\\p{N}-]+")

    private val TIME_BOUND_TYPES = setOf(ResourceType.NEWS, ResourceType.EVENT, ResourceType.COURSE)

    private val TIME_BOUND_WORDS =
      setOf(
        "news",
        "course",
        "courses",
        "certification",
        "certifications",
        "hackathon",
        "hackathons",
        "conference",
        "conferences",
        "event",
        "events",
        "meetup",
        "meetups",
        "summit",
        "webinar",
        "webinars",
        "workshop",
        "workshops",
        "notizie",
        "corso",
        "corsi",
        "conferenza",
        "conferenze",
        "evento",
        "eventi",
      )

    @JvmStatic
    fun of(request: SearchRequest, analyzed: AnalyzedQuery): IntentClass {
      request.intentClass?.let {
        return it
      }
      val types = request.filters.types
      val timeBound =
        (types.isNotEmpty() && TIME_BOUND_TYPES.containsAll(types)) ||
          request.filters.publishedAfter != null ||
          request.sort == SortOrder.NEWEST ||
          analyzed.normalized.split(NON_WORD).any { it in TIME_BOUND_WORDS }
      return if (timeBound) TIME_BOUND else GENERAL
    }
  }
}

data class WebFreshness(
  val timeBound: Duration = Duration.ofHours(24),
  val general: Duration = Duration.ofHours(48),
  val keep: Duration = Duration.ofDays(7),
) {
  fun of(intentClass: IntentClass): Duration =
    if (intentClass == IntentClass.TIME_BOUND) timeBound else general
}

class WebSearchQuota
@JvmOverloads
constructor(
  private val counter: QuotaCounter,
  private val keys: QuotaKeys,
  private val limits: DailyLimits = DailyLimits(),
  private val globalDailyCredits: Supplier<Long?> = Supplier { null },
) {
  fun take(asker: Asker?): QuotaDecision {
    val global = global()
    if (asker == null) {
      return ask(null, 0, global) { null }
    }
    synchronized(asker) {
      val decision =
        when (val earlier = asker.decision) {
          is QuotaDecision.OverLimit -> earlier
          is QuotaDecision.Allowed ->
            ask(null, 0, global) { earlier.left }
              .let { if (it is QuotaDecision.Allowed) earlier else it }
          QuotaDecision.Unavailable,
          null -> {
            val limit = limits.of(asker.feature)
            ask(own(asker), limit, global) { used ->
                WebSearchesLeft(asker.feature, limit, maxOf(0, limit - used.toInt()))
              }
              .also { asker.holdsWebSearch = it is QuotaDecision.Allowed }
          }
        }
      asker.decision = decision
      if (decision is QuotaDecision.Allowed) asker.callsNotSettled++
      return decision
    }
  }

  fun left(asker: Asker): WebSearchesLeft? {
    synchronized(asker) {
      when (val d = asker.decision) {
        is QuotaDecision.Allowed ->
          d.left?.let {
            return it
          }
        is QuotaDecision.OverLimit ->
          d.left?.let {
            return it
          }
        else -> {}
      }
    }
    val limit = limits.of(asker.feature)
    return try {
      WebSearchesLeft(asker.feature, limit, maxOf(0, limit - counter.used(own(asker)).toInt()))
    } catch (_: RuntimeException) {
      null
    }
  }

  fun refund() {
    try {
      counter.giveBack(global())
    } catch (_: RuntimeException) {}
  }

  fun answered(asker: Asker) {
    synchronized(asker) {
      settleOneCall(asker)
      asker.answeredByTheWeb = true
    }
  }

  fun giveBack(asker: Asker) {
    synchronized(asker) {
      settleOneCall(asker)
      if (asker.callsNotSettled > 0 || !asker.holdsWebSearch || asker.answeredByTheWeb) return
      if (!givenBack(own(asker))) return
      asker.holdsWebSearch = false
      asker.decision = null
    }
  }

  private fun settleOneCall(asker: Asker) {
    asker.callsNotSettled = maxOf(0, asker.callsNotSettled - 1)
  }

  private fun givenBack(counter: Counter): Boolean =
    try {
      this.counter.giveBack(counter)
      true
    } catch (_: RuntimeException) {
      false
    }

  fun creditsToday(): WebCredits? =
    try {
      WebCredits(counter.used(global()), globalDailyCredits.get())
    } catch (_: RuntimeException) {
      null
    }

  private fun global() = Counter(keys.global(), QuotaKeys.CREDITS)

  private fun own(asker: Asker) = Counter(keys.guest(asker.client), asker.feature.name.lowercase())

  private fun ask(
    own: Counter?,
    limit: Int,
    global: Counter,
    left: (Long) -> WebSearchesLeft?,
  ): QuotaDecision {
    val result =
      try {
        counter.take(own, limit, global, globalDailyCredits.get() ?: Long.MAX_VALUE)
      } catch (_: RuntimeException) {
        return QuotaDecision.Unavailable
      }
    return when (result.verdict()) {
      Verdict.TAKEN -> QuotaDecision.Allowed(left(result.used()))
      Verdict.LIMIT_REACHED,
      Verdict.GLOBAL_LIMIT_REACHED,
      null -> QuotaDecision.OverLimit(left(result.used()))
    }
  }
}
