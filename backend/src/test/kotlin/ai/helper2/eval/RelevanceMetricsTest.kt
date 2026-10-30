package ai.helper2.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every expected value is computed by hand in the comment above it. */
class RelevanceMetricsTest {

    private val eps = 1e-9

    @Test
    fun `nDCG at 10 divides the run's graded gain by the best order of all judged grades`() {
        // DCG run [3,2,0]: (2^3-1)/log2(2) + (2^2-1)/log2(3) + 0 = 7 + 1.892789 = 8.892789
        // ideal from judged [3,2,1]: 7 + 1.892789 + (2^1-1)/log2(4) = 9.392789  ->  0.946768
        assertEquals(0.946768, RelevanceMetrics.ndcg(listOf(3, 2, 0), listOf(3, 2, 1), 10)!!, 1e-6)
    }

    @Test
    fun `nDCG counts only the first k positions`() {
        // k=1: run [0,3] -> 0; ideal [3] -> 7
        assertEquals(0.0, RelevanceMetrics.ndcg(listOf(0, 3), listOf(3), 1)!!, eps)
    }

    @Test
    fun `nDCG is undefined for a query without any graded judgement`() {
        assertNull(RelevanceMetrics.ndcg(listOf(0, 0), listOf(0, 0), 10))
    }

    @Test
    fun `reciprocal rank is one over the position of the first relevant result`() {
        // relevant = grade >= 2: first at position 3
        assertEquals(1.0 / 3, RelevanceMetrics.reciprocalRank(listOf(0, 1, 2, 3)), eps)
        assertEquals(0.0, RelevanceMetrics.reciprocalRank(listOf(1, 1, 0)), eps)
    }

    @Test
    fun `recall is the share of relevant judged resources found`() {
        assertEquals(0.5, RelevanceMetrics.recall(setOf("a", "c"), setOf("a", "b", "c", "d"))!!, eps)
        assertNull(RelevanceMetrics.recall(setOf("a"), emptySet()))
    }

    @Test
    fun `duplicates in the top k are results with the same name as a higher one`() {
        // keys: postgresmcp, postgresmcp (npm scope dropped), y, postgresmcp -> 2 duplicates in the first 4
        val titles = listOf("crystaldba/postgres-mcp", "@acme/postgres_mcp", "x/y", "Postgres MCP")
        assertEquals(2, RelevanceMetrics.duplicates(titles, 4))
        assertEquals(1, RelevanceMetrics.duplicates(titles, 2))
    }

    @Test
    fun `unjudged share counts results in the top k that no judge has graded`() {
        // [3, -, 0, -] -> 2 of 4
        assertEquals(0.5, RelevanceMetrics.unjudgedShare(listOf(3, null, 0, null), 10), eps)
        assertEquals(0.0, RelevanceMetrics.unjudgedShare(emptyList(), 10), eps)
    }

    @Test
    fun `kappa corrects the judges' agreement for the agreement expected by chance`() {
        // 4 items, agree on 3: observed 0.75. Judge A: 2,2,0,0 -> p(2)=p(0)=0.5; judge B: 2,2,0,2 -> p(2)=0.75, p(0)=0.25
        // chance = 0.5*0.75 + 0.5*0.25 = 0.5; kappa = (0.75-0.5)/(1-0.5) = 0.5
        assertEquals(0.5, RelevanceMetrics.cohensKappa(listOf(2 to 2, 2 to 2, 0 to 0, 0 to 2))!!, eps)
        assertEquals(1.0, RelevanceMetrics.cohensKappa(listOf(3 to 3, 0 to 0))!!, eps)
        assertNull(RelevanceMetrics.cohensKappa(emptyList()))
    }

    @Test
    fun `paired bootstrap of a constant difference is that difference`() {
        val ci = RelevanceMetrics.pairedBootstrap(listOf(0.2, 0.5, 0.7), listOf(0.3, 0.6, 0.8))
        assertEquals(0.1, ci.meanDifference, 1e-9)
        assertEquals(0.1, ci.low, 1e-9)
        assertEquals(0.1, ci.high, 1e-9)
        assertTrue(ci.excludesZero)
    }

    @Test
    fun `paired bootstrap of identical runs is zero`() {
        val ci = RelevanceMetrics.pairedBootstrap(listOf(0.2, 0.5), listOf(0.2, 0.5))
        assertEquals(0.0, ci.low, eps)
        assertEquals(0.0, ci.high, eps)
        assertTrue(!ci.excludesZero)
    }

    @Test
    fun `paired bootstrap of differences that cancel out includes zero and is repeatable`() {
        // differences +0.5, -0.5, +0.5, -0.5: mean 0, resampled means spread on both sides of 0
        val a = listOf(0.0, 0.5, 0.0, 0.5)
        val b = listOf(0.5, 0.0, 0.5, 0.0)
        val ci = RelevanceMetrics.pairedBootstrap(a, b)
        assertEquals(0.0, ci.meanDifference, eps)
        assertTrue(ci.low < 0 && ci.high > 0, "$ci")
        assertEquals(ci, RelevanceMetrics.pairedBootstrap(a, b))
    }
}
