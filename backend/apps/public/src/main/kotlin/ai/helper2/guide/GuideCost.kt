package ai.helper2.guide

import ai.helper2.platform.ClientKey
import ai.helper2.platform.llm.CallCounter
import ai.helper2.platform.llm.DailyBudget
import ai.helper2.platform.quota.ClientBudgets
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.search.SkipReason
import ai.helper2.shared.AudiencePath
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ClientQuota
@JvmOverloads
constructor(
  private val questionsPerDay: Int,
  clock: Clock,
  keys: QuotaKeys,
  counter: CallCounter = CallCounter.NONE,
  maxClients: Int = MAX_CLIENTS,
  private val freeAnswersPerDay: Int = questionsPerDay,
  networkQuestionsPerDay: Int? = null,
) {
  private val clients =
    ClientBudgets("guide-client:", questionsPerDay, clock, keys, counter, maxClients)
  private val networks = networkQuestionsPerDay?.let {
    ClientBudgets("guide-network:", it, clock, keys, counter, maxClients)
  }
  private val networkFreeAnswersPerDay =
    freeAnswersOfANetwork(questionsPerDay, freeAnswersPerDay, networkQuestionsPerDay ?: 0)

  val limit: Int
    get() = maxOf(0, questionsPerDay)

  class Reservation
  internal constructor(
    internal val budget: DailyBudget,
    internal val network: DailyBudget?,
    internal val day: LocalDate,
  ) {
    val id: String = UUID.randomUUID().toString()
    internal val givenBack = AtomicBoolean()
  }

  fun left(client: String): Int {
    val network = networks?.left(ClientKey.network(client)) ?: Int.MAX_VALUE
    return minOf(clients.left(client), network)
  }

  fun reserve(client: String): Reservation? {
    val day = clients.today()
    val network = networks?.budgetOf(ClientKey.network(client))
    if (network != null && !network.tryAcquire()) return null
    val own = clients.budgetOf(client)
    if (!own.tryAcquire()) {
      network?.undo(day)
      return null
    }
    return Reservation(own, network, day)
  }

  fun refusedForTheNetwork(client: String): Boolean =
    networks != null && networks.left(ClientKey.network(client)) == 0 && clients.left(client) > 0

  fun giveBack(reservation: Reservation): CallCounter.GiveBack {
    if (!reservation.givenBack.compareAndSet(false, true)) {
      return CallCounter.GiveBack.ALREADY_GIVEN_BACK
    }
    val outcome =
      reservation.budget.giveBack(reservation.day, reservation.id, maxOf(0, freeAnswersPerDay))
    if (outcome == CallCounter.GiveBack.GIVEN_BACK)
      reservation.network?.giveBack(reservation.day, reservation.id, networkFreeAnswersPerDay)
    return outcome
  }

  internal val clientsInMemory: Int
    get() = clients.inMemory()

  companion object {
    const val MAX_CLIENTS = ClientBudgets.MAX_CLIENTS

    @JvmStatic fun spendsAQuestion(answer: GuideAnswer): Boolean = answer.hasAiAnswer()

    internal fun freeAnswersOfANetwork(
      questionsPerDay: Int,
      freeAnswersPerDay: Int,
      networkQuestionsPerDay: Int,
    ): Int {
      val free = maxOf(0, freeAnswersPerDay).toLong()
      val clientsOfANetwork =
        if (questionsPerDay <= 0) 1L
        else maxOf(1L, (networkQuestionsPerDay.toLong() + questionsPerDay - 1) / questionsPerDay)
      return minOf(free * clientsOfANetwork, Int.MAX_VALUE.toLong()).toInt()
    }
  }
}

internal fun GuideAnswer.hasAiAnswer(): Boolean =
  recommendations.any { it.aiReasoned } ||
    comparison?.rows?.isNotEmpty() == true ||
    explanation?.sentences?.isNotEmpty() == true

internal fun sha256Hex(text: String): String =
  HexFormat.of()
    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

interface GuideAnswerStore {
  fun get(key: String): GuideAnswer?

  fun put(key: String, answer: GuideAnswer, ttl: Duration)
}

class GuideAnswerCache(
  private val ttl: Duration,
  private val clock: Clock,
  private val store: GuideAnswerStore,
) {
  fun get(key: String): GuideAnswer? {
    val stored = runCatching { store.get(key) }.getOrNull() ?: return null
    val expiresAt = stored.generatedAt.plus(ttlOf(stored))
    return stored.takeIf { clock.instant().isBefore(expiresAt) && isCacheable(it) }
  }

  fun put(key: String, answer: GuideAnswer): Instant? {
    val expiresAt = answer.generatedAt.plus(ttlOf(answer))
    val left = Duration.between(clock.instant(), expiresAt)
    if (!isCacheable(answer) || !left.isPositive) return null
    return expiresAt.takeIf { runCatching { store.put(key, answer, left) }.isSuccess }
  }

  private fun ttlOf(answer: GuideAnswer): Duration {
    val dated = answer.window != null || answer.intent == GuideIntent.WHEN
    val windowed = if (dated) minOf(ttl, WINDOWED_TTL) else ttl
    val life =
      if (answer.intent == GuideIntent.WHEN) minOf(windowed, untilNextUtcDay(answer.generatedAt))
      else windowed
    val kept = if (answer.isNoPick()) minOf(life, NO_PICK_TTL) else life
    return answer.webFreshUntil?.let { minOf(kept, Duration.between(answer.generatedAt, it)) }
      ?: kept
  }

  companion object {
    @JvmStatic
    fun isCacheable(a: GuideAnswer): Boolean {
      if (!a.modelUsed || a.status == GuideStatus.NEEDS_CLARIFICATION) return false
      if (webSkippedForQuota(a)) return false
      if (a.intent == GuideIntent.WHEN)
        return a.status == GuideStatus.OK && a.recommendations.isNotEmpty()
      if (a.comparison.hasCellsNotChecked()) return false
      if (a.isNoPick()) return a.status != GuideStatus.LLM_UNAVAILABLE
      val picksChecked =
        a.recommendations.none { it.aiReasoned && it.reasonCheck == ReasonCheck.NOT_CHECKED }
      val comparisonChecked =
        a.comparison
          ?.rows
          .orEmpty()
          .flatMap { it.cells }
          .none { it.text != null && it.reasonCheck == ReasonCheck.NOT_CHECKED }
      val explanationChecked =
        a.explanation?.sentences.orEmpty().none { it.reasonCheck == ReasonCheck.NOT_CHECKED }
      return picksChecked && comparisonChecked && explanationChecked
    }

    private fun Comparison?.hasCellsNotChecked() = this != null && cellsNotChecked > 0

    private fun GuideAnswer.isNoPick() = intent != GuideIntent.WHEN && !hasAiAnswer()

    private fun untilNextUtcDay(at: Instant): Duration {
      val midnight =
        LocalDate.ofInstant(at, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC)
      return Duration.between(at, midnight.toInstant())
    }

    private fun webSkippedForQuota(a: GuideAnswer) =
      a.search.statuses.any { it.skipReason in QUOTA_SKIPS }

    private val QUOTA_SKIPS = setOf(SkipReason.DAILY_LIMIT, SkipReason.QUOTA_UNAVAILABLE)

    @JvmField val WINDOWED_TTL: Duration = Duration.ofHours(6)

    @JvmField val NO_PICK_TTL: Duration = Duration.ofMinutes(10)

    private const val SHAPE = "v3"

    @JvmStatic
    @JvmOverloads
    fun key(
      request: String,
      path: AudiencePath?,
      config: String,
      clarification: String? = null,
      drops: Drops = Drops.NONE,
    ): String =
      sha256Hex(
        listOf(
            SHAPE,
            normalized(request),
            path?.name ?: "-",
            config,
            clarification?.let { "c:" + normalized(it) } ?: "-",
            drops.key.ifEmpty { "-" },
          )
          .joinToString("|")
      )

    private val QUOTES = Regex("[\"`«»‹›“”„‟]")
    private val APOSTROPHES = Regex("[‘’‚‛ʼ]")
    private val QUOTING_APOSTROPHE = Regex("(?<![\\p{L}\\p{N}])'|'(?![\\p{L}\\p{N}])")
    private val INVISIBLE = Regex("[​-‍⁠﻿]")
    private val SPACES = Regex("[\\p{Z}\\s]+")
    private val TRAILING_PUNCTUATION = Regex("[\\s.?!,;:…¿¡]+$")

    @JvmStatic
    fun normalized(text: String): String {
      val plain =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
          .lowercase(Locale.ROOT)
          .replace(INVISIBLE, "")
          .replace(APOSTROPHES, "'")
          .replace(QUOTES, " ")
          .replace(QUOTING_APOSTROPHE, " ")
      return plain.replace(SPACES, " ").replace(TRAILING_PUNCTUATION, "").trim()
    }
  }
}
