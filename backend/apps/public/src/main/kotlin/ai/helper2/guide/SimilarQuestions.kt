package ai.helper2.guide

import ai.helper2.shared.AudiencePath
import java.time.Clock
import java.time.Instant
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

data class ReadingKey(
  val intent: GuideIntent,
  val subjects: List<String>,
  val constraints: List<String>,
  val window: DateWindow?,
  val wantsReading: Boolean,
  val aboutEvent: Boolean,
  val asksBack: Boolean,
) {
  val text: String
    get() =
      listOf(
          intent.name,
          subjects
            .map(::loose)
            .let { if (intent == GuideIntent.COMPARE) it.sorted() else it }
            .joinToString(","),
          constraints.map(::loose).sorted().joinToString(","),
          window?.let { "${it.from}..${it.to}" } ?: "-",
          wantsReading,
          aboutEvent,
          asksBack,
        )
        .joinToString("|")

  private fun loose(name: String) = name.lowercase().replace(LOOSE, " ").trim()

  private companion object {
    val LOOSE = Regex("[\\s_\\-]+")
  }
}

fun interface QuestionEmbedder {
  fun embed(question: String): FloatArray
}

class IndexedQuestion(val answerKey: String, val vector: FloatArray, val expiresAt: Instant)

fun interface SimilarQuestionRetention {
  fun deleteExpiredBefore(cutoff: Instant): Int
}

data class Neighbour(val answerKey: String, val similarity: Double)

interface SimilarQuestionIndex {
  fun nearest(group: String, vector: FloatArray, now: Instant): Neighbour?

  fun add(group: String, question: IndexedQuestion)
}

class SimilarQuestions(
  val threshold: Double,
  private val model: String,
  private val embedder: QuestionEmbedder,
  private val index: SimilarQuestionIndex,
  private val clock: Clock,
  private val build: String = "",
) {
  private val log = LoggerFactory.getLogger(SimilarQuestions::class.java)

  init {
    require(threshold > 0.0 && threshold <= 1.0) {
      "similar-question threshold must be in (0, 1], got $threshold"
    }
  }

  class Probe internal constructor(val group: String, val vector: FloatArray)

  fun probe(question: String, config: String, path: AudiencePath?, reading: ReadingKey): Probe? {
    val vector =
      try {
        embedder.embed(GuideAnswerCache.normalized(question))
      } catch (e: RuntimeException) {
        log.debug("question not embedded, no similar-question lookup: {}", e.javaClass.simpleName)
        return null
      }
    return Probe(
      sha256Hex(
        listOf(model, build, config, path?.name ?: "-", reading.text).joinToString("\u0000")
      ),
      vector,
    )
  }

  fun nearest(probe: Probe): String? =
    index
      .nearest(probe.group, probe.vector, clock.instant())
      ?.takeIf { it.similarity >= threshold }
      ?.answerKey

  fun remember(probe: Probe, answerKey: String, expiresAt: Instant) =
    index.add(probe.group, IndexedQuestion(answerKey, probe.vector, expiresAt))

  companion object {
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
