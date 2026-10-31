package ai.helper2.guide

import ai.helper2.search.NOW
import ai.helper2.shared.AudiencePath
import ai.helper2.testing.MovableClock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** What the PostgreSQL index holds, in memory: one question per answer key, by group; exact cosine search. */
class MemoryQuestionIndex : SimilarQuestionIndex {
    val groups = mutableMapOf<String, MutableMap<String, IndexedQuestion>>()
    override fun nearest(group: String, vector: FloatArray, now: Instant): Neighbour? =
        groups[group].orEmpty().values.filter { now.isBefore(it.expiresAt) }
            .map { Neighbour(it.answerKey, SimilarQuestions.cosine(vector, it.vector)) }
            .maxByOrNull { it.similarity }
    override fun add(group: String, question: IndexedQuestion) {
        groups.getOrPut(group) { mutableMapOf() }[question.answerKey] = question
    }
    val size: Int get() = groups.values.sumOf { it.size }
}

/** What the shared cache holds for the guide, in memory. */
class MemoryAnswerStore : GuideAnswerStore {
    val entries = mutableMapOf<String, GuideAnswer>()
    var puts = 0
    override fun get(key: String) = entries[key]
    override fun put(key: String, answer: GuideAnswer, ttl: java.time.Duration) {
        puts++
        entries[key] = answer
    }
}

/** Embeds by a fixed table of normalised questions; any other text fails, as an unavailable API would. */
class TableEmbedder(private val vectors: Map<String, FloatArray>) : QuestionEmbedder {
    val asked = mutableListOf<String>()
    override fun embed(question: String): FloatArray {
        asked += question
        return vectors[question] ?: throw IllegalStateException("no vector for $question")
    }
}

class SimilarQuestionsTest {

    private val clock = MovableClock(NOW)
    private val reading = ReadingKey(GuideIntent.SUGGEST, emptyList(), listOf("free"), null, false, false, false)

    private fun similar(index: SimilarQuestionIndex, vararg vectors: Pair<String, FloatArray>, threshold: Double = 0.9) =
        SimilarQuestions(threshold, "embed-model", TableEmbedder(vectors.toMap()), index, clock)

    @Test
    fun `the closest stored question at or above the threshold gives its answer key`() {
        val index = MemoryQuestionIndex()
        val s = similar(
            index,
            "a free tool to chat with pdfs" to floatArrayOf(1f, 0f, 0f),
            "free tool for chatting with pdf files" to floatArrayOf(0.95f, 0.3122f, 0f),
            "free pdf chat app" to floatArrayOf(0.99f, 0.141f, 0f),
        )
        s.remember(s.probe("A free tool to chat with PDFs?", "c", null, reading)!!, "k1", NOW.plusSeconds(60))
        s.remember(s.probe("free pdf chat app", "c", null, reading)!!, "k2", NOW.plusSeconds(60))

        assertEquals("k2", s.nearest(s.probe("free tool for chatting with pdf files", "c", null, reading)!!))
    }

    @Test
    fun `a question below the threshold, or whose answer expired, is not reused`() {
        val index = MemoryQuestionIndex()
        val s = similar(index, "a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0.8f, 0.6f), "c" to floatArrayOf(1f, 0.01f))
        s.remember(s.probe("a", "c", null, reading)!!, "ka", NOW.plusSeconds(60))

        assertNull(s.nearest(s.probe("b", "c", null, reading)!!), "cosine 0.8 is below 0.9")
        clock.now = NOW.plusSeconds(60)
        assertNull(s.nearest(s.probe("c", "c", null, reading)!!), "the answer expired")
    }

    @Test
    fun `only questions of the same model, config, path and reading are compared`() {
        val index = MemoryQuestionIndex()
        val v = floatArrayOf(1f, 0f)
        val s = similar(index, "q" to v)
        s.remember(s.probe("q", "c", null, reading)!!, "k", NOW.plusSeconds(60))

        assertEquals("k", s.nearest(s.probe("q", "c", null, reading)!!))
        assertNull(s.nearest(s.probe("q", "other config", null, reading)!!))
        assertNull(s.nearest(s.probe("q", "c", AudiencePath.USE_AI, reading)!!))
        assertNull(s.nearest(s.probe("q", "c", null, reading.copy(constraints = listOf("paid")))!!))
        val otherModel = SimilarQuestions(0.9, "another-model", TableEmbedder(mapOf("q" to v)), index, clock)
        assertNull(otherModel.nearest(otherModel.probe("q", "c", null, reading)!!))
    }

    @Test
    fun `a question that cannot be embedded has no probe`() {
        assertNull(similar(MemoryQuestionIndex()).probe("unknown", "c", null, reading))
    }

    @Test
    fun `the threshold must be a cosine above zero`() {
        assertFailsWith<IllegalArgumentException> { similar(MemoryQuestionIndex(), threshold = 0.0) }
        assertFailsWith<IllegalArgumentException> { similar(MemoryQuestionIndex(), threshold = 1.01) }
    }

    @Test
    fun `cosine ignores length and gives 0 for vectors that cannot be compared`() {
        assertEquals(1.0, SimilarQuestions.cosine(floatArrayOf(1f, 1f), floatArrayOf(3f, 3f)), 1e-9)
        assertEquals(0.0, SimilarQuestions.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertEquals(0.0, SimilarQuestions.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)))
        assertEquals(0.0, SimilarQuestions.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)))
    }

    private fun key(intent: GuideIntent, subjects: List<String>, constraints: List<String> = emptyList()) =
        ReadingKey(intent, subjects, constraints, null, false, false, false).text

    @Test
    fun `a comparison's subjects are a set, other subjects keep their order`() {
        assertEquals(key(GuideIntent.COMPARE, listOf("Ollama", "LM Studio")), key(GuideIntent.COMPARE, listOf("lm studio", "ollama")))
        assertNotEquals(key(GuideIntent.SUGGEST, listOf("python", "rust")), key(GuideIntent.SUGGEST, listOf("rust", "python")))
        assertNotEquals(key(GuideIntent.HOW_TO, listOf("pytorch", "onnx")), key(GuideIntent.HOW_TO, listOf("onnx", "pytorch")))
    }

    @Test
    fun `names compare without case, hyphens or underscores, constraints in any order`() {
        assertEquals(key(GuideIntent.COMPARE, listOf("faster-whisper", "Whisper")), key(GuideIntent.COMPARE, listOf("whisper", "Faster Whisper")))
        assertEquals(key(GuideIntent.SUGGEST, emptyList(), listOf("free", "Open source")), key(GuideIntent.SUGGEST, emptyList(), listOf("open-source", "free")))
        assertNotEquals(key(GuideIntent.SUGGEST, listOf("llama.cpp")), key(GuideIntent.SUGGEST, listOf("llama cpp")))
    }

    @Test
    fun `intent, constraints, window, reading types, events and the ask-back check are part of the reading`() {
        val base = ReadingKey(GuideIntent.WHAT_IS, listOf("gemini"), emptyList(), null, false, false, true)
        val month = DateWindow(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        listOf(
            base.copy(intent = GuideIntent.ALTERNATIVES),
            base.copy(constraints = listOf("free")),
            base.copy(window = month),
            base.copy(window = month.copy(from = LocalDate.of(2026, 10, 24))),
            base.copy(wantsReading = true),
            base.copy(aboutEvent = true),
            base.copy(asksBack = false),
        ).forEach { assertNotEquals(base.text, it.text, "$it") }
    }

    @Test
    fun `the probe embeds the normalised question`() {
        val embedder = TableEmbedder(mapOf("what's mcp" to floatArrayOf(1f)))
        SimilarQuestions(0.9, "m", embedder, MemoryQuestionIndex(), clock).probe("  “What’s MCP?” ", "c", null, reading)

        assertEquals(listOf("what's mcp"), embedder.asked)
    }

}
