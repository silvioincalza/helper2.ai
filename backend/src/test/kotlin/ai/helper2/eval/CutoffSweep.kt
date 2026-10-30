package ai.helper2.eval

import ai.helper2.shared.CanonicalUrl

/** Vector search quality at one maximum cosine distance, averaged over the judged queries. */
data class CutoffPoint(
    val maxDistance: Double,
    val ndcg10: Double?,
    val recall10: Double?,
    /** Mean results kept per query: what the cutoff lets through. */
    val keptPerQuery: Double,
    /** Mean results kept for the nonsense control queries: should be 0. */
    val keptPerControlQuery: Double?,
)

/**
 * Replays the semantic source's cutoff on recorded nearest neighbours (closest first, no cutoff): at each
 * candidate distance, which results would have been returned and how good they are (ADR 0012, SC-7).
 */
object CutoffSweep {

    val DEFAULT_CUTOFFS: List<Double> = (8..18).map { it * 0.05 }

    fun sweep(snapshots: Collection<SearchSnapshot>, qrels: Qrels, controlIds: Set<String>,
              cutoffs: List<Double> = DEFAULT_CUTOFFS): List<CutoffPoint> {
        val withVectors = snapshots.filter { it.semantic != null }
        return cutoffs.map { c ->
            val kept = withVectors.associate { s -> s.queryId to s.semantic!!.filter { it.distance <= c + 1e-9 }.take(30) }
            val judged = withVectors.filter { s -> qrels.of(s.queryId).values.any { it > 0 } }
            val ndcg = judged.mapNotNull { s ->
                val grades = qrels.of(s.queryId)
                RelevanceMetrics.ndcg(kept.getValue(s.queryId).map { grades[key(it.canonicalUrl)] }, grades.values, 10)
            }
            val recall = judged.mapNotNull { s ->
                val grades = qrels.of(s.queryId)
                RelevanceMetrics.recall(kept.getValue(s.queryId).take(10).map { key(it.canonicalUrl) }.toSet(),
                    grades.filterValues { it >= RelevanceMetrics.RELEVANT }.keys)
            }
            val controls = kept.filterKeys { it in controlIds }.values.map { it.size.toDouble() }
            CutoffPoint(c, ndcg.averageOrNull(), recall.averageOrNull(), kept.values.map { it.size.toDouble() }.averageOrNull() ?: 0.0,
                controls.averageOrNull())
        }
    }

    fun table(points: List<CutoffPoint>): String = buildString {
        appendLine("| max distance | nDCG@10 | Recall@10 | kept per query | kept per nonsense query |")
        appendLine("|---|---|---|---|---|")
        points.forEach { p ->
            appendLine("| ${SearchEvaluation.fmt(p.maxDistance)} | ${SearchEvaluation.fmt(p.ndcg10)} | ${SearchEvaluation.fmt(p.recall10)} | " +
                "${SearchEvaluation.fmt(p.keptPerQuery)} | ${SearchEvaluation.fmt(p.keptPerControlQuery)} |")
        }
    }

    private fun key(url: String) = CanonicalUrl.of(url)?.value ?: url

    private fun List<Double>.averageOrNull() = if (isEmpty()) null else average()
}
