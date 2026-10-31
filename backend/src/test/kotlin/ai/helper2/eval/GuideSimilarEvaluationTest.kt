package ai.helper2.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline evaluation of similar-question reuse (ADR 0041) on docs/eval/guide-similar.json and its recorded
 * embeddings. Part of every build: the table of the ADR is what this test computes, so a new recording, a changed
 * pair or a changed matching rule shows up here first.
 */
class GuideSimilarEvaluationTest {

    private val set = GuideSimilarEvaluation.set()
    private val vectors = GuideSimilarEvaluation.vectors()
    private val thresholds = listOf(0.80, 0.85, 0.90, 0.93, 0.94, 0.95, 0.96, 0.97, 0.98, 0.99)

    @Test
    fun `the set is written as the index embeds it, and every question has a recorded vector`() {
        assertEquals(emptyList(), GuideSimilarEvaluation.notNormalised(set))
        GuideSimilarEvaluation.questions(set).forEach { assertEquals(vectors.dimensions, vectors.of(it).size, it) }
        assertEquals(25, set.pairs.count { it.shouldReuse })
        assertEquals(30, set.pairs.count { !it.shouldReuse })
    }

    @Test
    fun `with the reading, no must-not pair is reused from 0_96 up`() {
        val sweep = GuideSimilarEvaluation.sweep(set, vectors, thresholds, withReading = true)
        report("embedding and reading (production rule)", sweep)

        val at = sweep.associateBy { it.threshold }
        assertEquals(0, at.getValue(0.96).falseReuses)
        assertEquals(9, at.getValue(0.96).reused)
        assertTrue(at.getValue(0.95).falseReuses > 0, "0.95 lets \"this year\" and \"last year\" share an answer")
    }

    @Test
    fun `the embedding alone cannot tell a direction apart from a paraphrase`() {
        val sweep = GuideSimilarEvaluation.sweep(set, vectors, thresholds, withReading = false)
        report("embedding alone", sweep)

        val at = sweep.associateBy { it.threshold }
        assertTrue(at.getValue(0.98).falseReuses > 0, "\"python bindings for rust\" is 0.986 from its reverse")
        assertEquals(0, at.getValue(0.99).falseReuses)
    }

    private fun report(title: String, sweep: List<SimilarPoint>) {
        println("guide similar-question evaluation, $title (${vectors.model}):")
        println("| threshold | reused (of ${sweep.first().shouldReuse}) | recall | false reuses (of ${sweep.first().mustNot}) | precision |")
        sweep.forEach { p ->
            println("| %.2f | %d | %.2f | %d | %.2f |".format(p.threshold, p.reused, p.recall, p.falseReuses, p.precision))
        }
    }
}
