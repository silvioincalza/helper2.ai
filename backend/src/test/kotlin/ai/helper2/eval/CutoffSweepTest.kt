package ai.helper2.eval

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CutoffSweepTest {

    private fun snapshot(id: String, vararg nearest: Pair<String, Double>) = SearchSnapshot(id, id, Instant.EPOCH, listOf(id),
        emptySet(), emptyList(), nearest.map { SemanticHit(it.first, it.second) })

    private val qrels = Qrels.parse("""
        query_id	url	grade
        q1	https://x.org/a	3
        q1	https://x.org/b	2
    """.trimIndent().lines())

    private val snapshots = listOf(
        snapshot("q1", "https://x.org/a" to 0.30, "https://x.org/c" to 0.50, "https://x.org/b" to 0.70),
        snapshot("x1", "https://x.org/z" to 0.82),
    )

    @Test
    fun `a tighter cutoff drops far results, good and bad alike`() {
        val (tight, wide) = CutoffSweep.sweep(snapshots, qrels, setOf("x1"), listOf(0.40, 0.80))
        // 0.40 keeps [a]: DCG 7; ideal [3,2]: 7 + 3/log2(3) = 8.892789 -> 0.787155; recall 1 of 2
        assertEquals(0.787155, tight.ndcg10!!, 1e-6)
        assertEquals(0.5, tight.recall10!!, 1e-9)
        assertEquals(0.5, tight.keptPerQuery, 1e-9)
        assertEquals(0.0, tight.keptPerControlQuery!!, 1e-9)
        // 0.80 keeps [a, c, b]: DCG 7 + 0 + 3/log2(4) = 8.5 -> 0.955831; recall 2 of 2; the nonsense query still 0
        assertEquals(0.955831, wide.ndcg10!!, 1e-6)
        assertEquals(1.0, wide.recall10!!, 1e-9)
        assertEquals(0.0, wide.keptPerControlQuery!!, 1e-9)
    }

    @Test
    fun `the nonsense control counts what a loose cutoff lets through`() {
        val loose = CutoffSweep.sweep(snapshots, qrels, setOf("x1"), listOf(0.85)).single()
        assertEquals(1.0, loose.keptPerControlQuery!!, 1e-9)
    }

    @Test
    fun `without recorded vectors nothing is measured`() {
        val point = CutoffSweep.sweep(listOf(snapshot("q1").copy(semantic = null)), qrels, emptySet(), listOf(0.5)).single()
        assertNull(point.ndcg10)
        assertEquals(0.0, point.keptPerQuery, 1e-9)
    }
}
