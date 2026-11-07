package ai.helper2.guide

import ai.helper2.platform.llm.CallCounter
import ai.helper2.platform.llm.DailyBudget
import ai.helper2.platform.quota.QuotaKeys
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

/**
 * Fresh (not cached) guide questions per client per UTC day. The per-minute rate limit alone lets
 * one client spend the global daily budget in about twenty minutes; past this quota the client
 * still gets an answer, from search only. Counted in [counter] (Redis) under the client's pseudonym
 * of the UTC day ([QuotaKeys.client], a keyed hash), so no address is stored; when the counter
 * fails, counting goes on in memory. The pseudonym changes at midnight UTC, when the quota starts
 * afresh anyway. These budgets are never listed in the back office.
 *
 * A question is taken before the model runs ([reserve]), so abuse stays bounded, and given back
 * when its answer has nothing from the AI ([giveBack], [spendsAQuestion]): at most
 * [freeAnswersPerDay] times a day, so a client cannot loop such questions for free.
 *
 * In memory, only today's clients are kept, at most [maxClients], the least recently seen leaving
 * first. A client that left keeps its count in [counter]; only the in-memory fallback count is
 * lost.
 */
class ClientQuota
@JvmOverloads
constructor(
  private val questionsPerDay: Int,
  private val clock: Clock,
  private val keys: QuotaKeys,
  counter: CallCounter = CallCounter.NONE,
  private val maxClients: Int = MAX_CLIENTS,
  private val freeAnswersPerDay: Int = questionsPerDay,
) {
  /**
   * Counted like any budget, never listed: a name per client must not reach the back office (ADR
   * 0026).
   */
  private val counter: CallCounter = counter.unlisted()

  private var day: LocalDate = today()
  private val budgets =
    object : LinkedHashMap<String, DailyBudget>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DailyBudget>?) =
        size > maxClients
    }

  /** Fresh questions per client and day. */
  val limit: Int
    get() = maxOf(0, questionsPerDay)

  /**
   * One question taken from a client's quota. [id] is random: it names the question for [giveBack]
   * and says nothing about the client.
   */
  class Reservation
  internal constructor(internal val budget: DailyBudget, internal val day: LocalDate) {
    val id: String = UUID.randomUUID().toString()
    internal val givenBack = AtomicBoolean()
  }

  /**
   * Questions [client] has left today, without taking one: the durable count when it answers, else
   * this replica's count in memory (as [reserve] counts).
   */
  fun left(client: String): Int {
    val name = nameOf(client)
    val durable =
      try {
        counter.current(name, today())
      } catch (_: RuntimeException) {
        null
      }
    val used =
      durable
        ?: synchronized(this) { if (day == today()) budgets[name]?.usedToday() ?: 0 else 0 }
          .toLong()
    return maxOf(0L, limit - used).toInt()
  }

  /** Takes one question from [client]'s quota for today; null when it is used up. */
  fun reserve(client: String): Reservation? {
    val name = nameOf(client)
    val now = today()
    val budget =
      synchronized(this) {
        if (now != day) {
          day = now
          budgets.clear()
        }
        budgets.getOrPut(name) { DailyBudget(questionsPerDay, clock, name, counter) }
      }
    // Outside the lock: the counter is a Redis call.
    return if (budget.tryAcquire()) Reservation(budget, now) else null
  }

  /** [reserve] without the reservation: false when the quota is used up. */
  fun tryAcquire(client: String): Boolean = reserve(client) != null

  /**
   * Gives [reservation]'s question back: its answer turned out free. Once per reservation, also
   * across replicas and retries; at most [freeAnswersPerDay] a day per client; never below 0.
   */
  fun giveBack(reservation: Reservation): CallCounter.GiveBack {
    if (!reservation.givenBack.compareAndSet(false, true)) {
      return CallCounter.GiveBack.ALREADY_GIVEN_BACK
    }
    return reservation.budget.giveBack(
      reservation.day,
      reservation.id,
      maxOf(0, freeAnswersPerDay),
    )
  }

  /** Clients held in memory right now. */
  internal val clientsInMemory: Int
    get() = synchronized(this) { budgets.size }

  private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

  private fun nameOf(client: String) = "guide-client:" + keys.client(client)

  companion object {
    /**
     * About 2 MB of budgets; far more distinct clients than a day of the guide's 600 model calls
     * can serve.
     */
    const val MAX_CLIENTS = 10_000

    /**
     * Whether [answer] spends one of the asker's daily questions (ADR 0017): only when the AI
     * answered, with at least one AI pick, a comparison with rows or an explanation with sentences.
     * An answer with no pick ("Nothing clearly fits your need", no candidates, model down, a
     * question back, a comparison with every cell refused) is free.
     */
    @JvmStatic fun spendsAQuestion(answer: GuideAnswer): Boolean = answer.hasAiAnswer()
  }
}

/**
 * The AI answered: at least one AI pick, a comparison with rows or an explanation with sentences.
 */
internal fun GuideAnswer.hasAiAnswer(): Boolean =
  recommendations.any { it.aiReasoned } ||
    comparison?.rows?.isNotEmpty() == true ||
    explanation?.sentences?.isNotEmpty() == true

internal fun sha256Hex(text: String): String =
  HexFormat.of()
    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

/**
 * Where guide answers are kept (the shared cache in PostgreSQL, ADR 0017): on disk and shared by
 * every replica, so a restart does not throw away answers that cost three model calls each. An
 * unavailable store or an unreadable entry is a miss, never an error.
 */
interface GuideAnswerStore {
  fun get(key: String): GuideAnswer?

  /** Keeps [answer] under [key] for [ttl], replacing what the key held. */
  fun put(key: String, answer: GuideAnswer, ttl: Duration)
}

/**
 * Recent AI-reasoned answers by normalised request, so a repeated question costs no model call and
 * answers at once (judges and visitors mostly try the same example query). The answer keeps its
 * original generatedAt, so the page stays honest about its age. Degraded answers are never cached:
 * they would hide a provider recovery.
 *
 * Kept in [store]; an answer expires [ttl] after its generatedAt.
 */
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

  /**
   * Caches [answer] under [key] until [ttl] after its generatedAt, also when it is an older answer
   * reused for a similar question (ADR 0017): a copy never outlives the original. Returns when it
   * expires; null when the answer is not cached.
   */
  fun put(key: String, answer: GuideAnswer): Instant? {
    val expiresAt = answer.generatedAt.plus(ttlOf(answer))
    val left = Duration.between(clock.instant(), expiresAt)
    if (!isCacheable(answer) || !left.isPositive) return null
    return expiresAt.takeIf { runCatching { store.put(key, answer, left) }.isSuccess }
  }

  /**
   * The cache is read before the plan, so its key cannot hold the resolved window of "last month".
   * An answer with a window lives at most [WINDOWED_TTL] instead, which bounds how far its window
   * drifts from today's. An answer built on web results lives no longer than they are fresh
   * ([GuideAnswer.webFreshUntil]); one built on stale ones is not cached at all.
   */
  private fun ttlOf(answer: GuideAnswer): Duration {
    val life = if (answer.window != null) minOf(ttl, WINDOWED_TTL) else ttl
    return answer.webFreshUntil?.let { minOf(life, Duration.between(answer.generatedAt, it)) }
      ?: life
  }

  companion object {
    /**
     * Only complete AI answers: at least one AI-reasoned pick, and every AI reason checked. A
     * reason left NOT_CHECKED (the check failed or the budget ran out) would otherwise be served
     * unchecked for a day.
     */
    @JvmStatic
    fun isCacheable(a: GuideAnswer): Boolean {
      if (!a.modelUsed || a.status == GuideStatus.NEEDS_CLARIFICATION) return false
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
      return a.hasAiAnswer() && picksChecked && comparisonChecked && explanationChecked
    }

    /** Longest life of an answer with a window ("last month"): see [ttlOf]. */
    @JvmField val WINDOWED_TTL: Duration = Duration.ofHours(6)

    /**
     * Bumped when the answer's shape changes, so answers cached in the old shape are not served
     * after a deploy.
     */
    private const val SHAPE = "v3"

    /**
     * Same [normalized] question, same [config] (models, prompts, limits) → same key. A
     * clarification answer and removed parts of the reading ([drops]) are part of the question. The
     * key is a SHA-256 hash, so no store sees the question.
     */
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

    /**
     * The question as the cache key and the similar-question index see it: only changes that cannot
     * change the answer. Unicode compatibility form (NFKC: full-width letters, ligatures, no-break
     * spaces), lower case, quote marks removed (an apostrophe inside a word stays), invisible
     * characters removed, spaces collapsed, and the punctuation that ends the sentence removed.
     * Word order, inner punctuation ("c++", "node.js", "gpt-4") and every word stay: "python
     * bindings for rust" is not "rust bindings for python".
     */
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
