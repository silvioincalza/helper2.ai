package ai.helper2.eval

import ai.helper2.guide.DateWindow
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.ReadingKey
import ai.helper2.guide.SimilarQuestions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.Base64
import tools.jackson.module.kotlin.readValue

/** One question of docs/eval/guide-similar.json, with the reading a correct plan gives it (ADR 0032 grounding). */
data class SimilarCase(val text: String, val reading: LabelledReading)

data class LabelledReading(
    val intent: GuideIntent,
    val subjects: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val window: LabelledWindow? = null,
    val wantsReading: Boolean = false,
    val aboutEvent: Boolean = false,
    val asksBack: Boolean = false,
) {
    val key: ReadingKey
        get() = ReadingKey(intent, subjects, constraints, window?.let { DateWindow(LocalDate.parse(it.from), LocalDate.parse(it.to)) },
            wantsReading, aboutEvent, asksBack)
}

data class LabelledWindow(val from: String, val to: String)

/** A pair that must reuse one answer ("reuse": same need, other words) or must not ("distinct"). */
data class SimilarPair(val kind: String, val expect: String, val a: SimilarCase, val b: SimilarCase) {
    val shouldReuse: Boolean get() = expect == "reuse"
}

data class SimilarSet(val pairs: List<SimilarPair>)

/** Question embeddings recorded once by GuideSimilarRecorder: normalised question → float32 little endian, Base64. */
data class RecordedVectors(val model: String, val dimensions: Int, val vectors: Map<String, String>) {
    fun of(text: String): FloatArray {
        val raw = requireNotNull(vectors[text]) { "no recorded vector for \"$text\": run ./gradlew evalRecord --tests '*GuideSimilarRecorder'" }
        val bytes = ByteBuffer.wrap(Base64.getDecoder().decode(raw)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.remaining() / 4).also { bytes.asFloatBuffer().get(it) }
    }

    companion object {
        fun encode(vector: FloatArray): String {
            val bytes = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(vector)
            return Base64.getEncoder().encodeToString(bytes.array())
        }
    }
}

/** One threshold of the sweep: reuses of the pairs that should, false reuses of the pairs that must not. */
data class SimilarPoint(val threshold: Double, val reused: Int, val shouldReuse: Int, val falseReuses: Int, val mustNot: Int) {
    val recall: Double get() = reused.toDouble() / shouldReuse
    val precision: Double get() = if (reused + falseReuses == 0) 1.0 else reused.toDouble() / (reused + falseReuses)
}

/**
 * The offline evaluation of similar-question reuse (ADR 0041). A pair is reused when the cosine of its recorded
 * embeddings is at least the threshold and, with [withReading], its two readings are equal: the production rule.
 * Without the reading the sweep shows what the embedding alone would let through.
 */
object GuideSimilarEvaluation {
    val setFile: File get() = File(EvalFiles.docs, "guide-similar.json")
    val vectorsFile: File get() = File(EvalFiles.docs, "guide-similar-vectors.json")

    fun set(): SimilarSet = EvalFiles.json.readValue(setFile)

    fun vectors(): RecordedVectors = EvalFiles.json.readValue(vectorsFile)

    fun cosine(pair: SimilarPair, vectors: RecordedVectors) = SimilarQuestions.cosine(vectors.of(pair.a.text), vectors.of(pair.b.text))

    fun sweep(set: SimilarSet, vectors: RecordedVectors, thresholds: List<Double>, withReading: Boolean): List<SimilarPoint> {
        val scored = set.pairs.map { p -> Triple(p, cosine(p, vectors), p.a.reading.key.text == p.b.reading.key.text) }
        return thresholds.map { t ->
            val reused = scored.filter { (_, cos, sameReading) -> cos >= t && (sameReading || !withReading) }.map { it.first }
            SimilarPoint(
                t,
                reused.count { it.shouldReuse }, set.pairs.count { it.shouldReuse },
                reused.count { !it.shouldReuse }, set.pairs.count { !it.shouldReuse },
            )
        }
    }

    /** Every question of the set, as the index embeds it: already in normal form. */
    fun questions(set: SimilarSet): List<String> = set.pairs.flatMap { listOf(it.a.text, it.b.text) }.distinct()

    fun notNormalised(set: SimilarSet): List<String> = questions(set).filter { GuideAnswerCache.normalized(it) != it }
}
