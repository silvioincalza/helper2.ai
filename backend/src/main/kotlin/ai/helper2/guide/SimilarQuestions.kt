package ai.helper2.guide

import ai.helper2.shared.AudiencePath
import java.time.Clock
import java.time.Instant
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

/**
 * How a question was read, as far as the reading decides the answer (ADR 0041). Two questions with equal keys get
 * the same retrieval and the same answer rules; only their wording can differ. The planned search query is left
 * out on purpose: two wordings of one need are planned with different words, and the question embedding covers it.
 */
data class ReadingKey(
    val intent: GuideIntent,
    val subjects: List<String>,
    val constraints: List<String>,
    val window: DateWindow?,
    val wantsReading: Boolean,
    val aboutEvent: Boolean,
    val asksBack: Boolean,
) {
    /**
     * Stable text of the key. Subjects keep their order, because the order carries the direction ("python bindings
     * for rust"); a comparison's subjects are a set, because its columns are. Constraints are a set. Names compare
     * without case, hyphens or underscores ("faster-whisper" is "Faster Whisper").
     */
    val text: String
        get() = listOf(
            intent.name,
            subjects.map(::loose).let { if (intent == GuideIntent.COMPARE) it.sorted() else it }.joinToString(","),
            constraints.map(::loose).sorted().joinToString(","),
            window?.let { "${it.from}..${it.to}" } ?: "-",
            wantsReading, aboutEvent, asksBack,
        ).joinToString("|")

    private fun loose(name: String) = name.lowercase().replace(LOOSE, " ").trim()

    private companion object {
        val LOOSE = Regex("[\\s_\\-]+")
    }
}

/** Outbound port: the question embedding, with the model search uses (ADR 0012). Throws when it cannot embed. */
fun interface QuestionEmbedder {
    fun embed(question: String): FloatArray
}

/** A question whose answer is cached: its embedding, the cache key of its answer and when that answer expires. */
class IndexedQuestion(val answerKey: String, val vector: FloatArray, val expiresAt: Instant)

/** Outbound port: indexed questions past their expiry (ADR 0034). */
fun interface SimilarQuestionRetention {
    /** Deletes the indexed questions that expired before [cutoff]; returns how many. */
    fun deleteExpiredBefore(cutoff: Instant): Int
}

/** The stored question closest to a probe, by cosine similarity. */
data class Neighbour(val answerKey: String, val similarity: Double)

/**
 * Outbound port: the questions of each reading group whose answers are cached (PostgreSQL and pgvector, ADR 0041).
 * Disposable like the answer cache: an unavailable index finds nothing and forgets what it is given, never an error.
 */
interface SimilarQuestionIndex {
    /** The question of [group] whose answer is still cached at [now] and whose embedding is closest to [vector]. */
    fun nearest(group: String, vector: FloatArray, now: Instant): Neighbour?

    /** Indexes [question] in [group], replacing what its answer key held. */
    fun add(group: String, question: IndexedQuestion)
}

/**
 * Reuse of a cached answer for a question worded differently but read the same way (ADR 0041). Only after the exact
 * cache key missed and the planner read the question: the reading is the one the fresh answer would use, so no
 * model call is added to decide. A stored question matches when it is in the same group (same embedding model,
 * guide config, audience path and [ReadingKey]) and its cosine similarity is at least [threshold].
 */
class SimilarQuestions(
    val threshold: Double,
    private val model: String,
    private val embedder: QuestionEmbedder,
    private val index: SimilarQuestionIndex,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SimilarQuestions::class.java)

    init {
        require(threshold > 0.0 && threshold <= 1.0) { "similar-question threshold must be in (0, 1], got $threshold" }
    }

    /** A question to look up now and, once answered fresh, to index. */
    class Probe internal constructor(val group: String, val vector: FloatArray)

    /** The probe of [question] read as [reading]; null when it cannot be embedded, which is a miss. */
    fun probe(question: String, config: String, path: AudiencePath?, reading: ReadingKey): Probe? {
        val vector = try {
            embedder.embed(GuideAnswerCache.normalized(question))
        } catch (e: RuntimeException) {
            log.debug("question not embedded, no similar-question lookup: {}", e.javaClass.simpleName)
            return null
        }
        return Probe(sha256Hex(listOf(model, config, path?.name ?: "-", reading.text).joinToString("\u0000")), vector)
    }

    /** The answer key of the closest live question of the probe's group, if it is at least [threshold] close. */
    fun nearest(probe: Probe): String? =
        index.nearest(probe.group, probe.vector, clock.instant())?.takeIf { it.similarity >= threshold }?.answerKey

    /** Indexes a freshly answered question whose answer is cached under [answerKey] until [expiresAt]. */
    fun remember(probe: Probe, answerKey: String, expiresAt: Instant) =
        index.add(probe.group, IndexedQuestion(answerKey, probe.vector, expiresAt))

    companion object {
        /** Cosine similarity; 0 for vectors of different sizes or a zero vector. */
        @JvmStatic
        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size) return 0.0
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
        }
    }
}
