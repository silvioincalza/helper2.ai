package ai.helper2.eval

import ai.helper2.search.MergedResult
import java.util.Locale

/** Metrics of one ranked list for one query. Null where the query has no graded judgement. */
data class QueryScore(
    val queryId: String,
    val ndcg10: Double?,
    val mrr: Double?,
    val recall20: Double?,
    val duplicates10: Int,
    val unjudged10: Double,
)

/**
 * Replays every snapshot through production merge and ranking and scores it against the judgements: the
 * baseline (every source) and one ablation per source (that source left out). Deterministic: no network.
 */
class SearchEvaluation(
    private val queries: List<EvalQuery>,
    private val snapshots: Map<String, SearchSnapshot>,
    private val qrels: Qrels,
    private val replay: SearchReplay = SearchReplay(),
) {
    val recorded: List<EvalQuery> = queries.filter { it.id in snapshots }
    val sourceIds: List<String> = snapshots.values.flatMap { s -> s.sources.map { it.id } }.distinct().sorted()

    fun ranked(queryId: String, without: Set<String> = emptySet()): List<MergedResult> =
        replay.rank(snapshots.getValue(queryId), without)

    /** The best grade any URL of [r] has: its identity, each source's URL or repository. Null when unjudged. */
    fun grade(queryId: String, r: MergedResult): Int? {
        val judged = qrels.of(queryId)
        return urlsOf(r).mapNotNull { judged[it] }.maxOrNull()
    }

    fun score(queryId: String, without: Set<String> = emptySet()): QueryScore {
        val results = ranked(queryId, without)
        val grades = results.map { grade(queryId, it) }
        val judged = qrels.of(queryId)
        val relevant = judged.filterValues { it >= RelevanceMetrics.RELEVANT }.keys
        val graded = judged.values.any { it > 0 }
        val found = results.take(20).flatMap(::urlsOf).toSet()
        return QueryScore(
            queryId,
            ndcg10 = RelevanceMetrics.ndcg(grades, judged.values, 10),
            mrr = if (graded) RelevanceMetrics.reciprocalRank(grades) else null,
            recall20 = RelevanceMetrics.recall(found, relevant),
            duplicates10 = RelevanceMetrics.duplicates(results.map { it.title }, 10),
            unjudged10 = RelevanceMetrics.unjudgedShare(grades, 10),
        )
    }

    fun scores(without: Set<String> = emptySet()): List<QueryScore> = recorded.map { score(it.id, without) }

    /**
     * What to label next: the union of the top 20 of the baseline and of every ablation, plus the 10 nearest catalog
     * vectors at any distance (for the cutoff sweep), minus what is judged.
     */
    fun pool(): String = buildString {
        appendLine("query_id\turl\tgrade\tquery\ttitle\tsources")
        for (q in recorded) {
            val variants = listOf(emptySet<String>()) + sourceIds.map { setOf(it) }
            val seen = LinkedHashMap<String, MergedResult>()
            variants.forEach { v -> ranked(q.id, v).take(20).forEach { seen.putIfAbsent(it.canonicalUrl.value, it) } }
            val judged = qrels.of(q.id)
            seen.values.filter { grade(q.id, it) == null }.forEach { r ->
                appendLine(listOf(q.id, r.canonicalUrl.value, "", q.text, r.title.replace('\t', ' '),
                    r.sources.joinToString(",") { it.sourceId }).joinToString("\t"))
            }
            val pooled = seen.values.flatMap(::urlsOf).toSet()
            snapshots.getValue(q.id).semantic.orEmpty().take(10)
                .filter { it.canonicalUrl !in pooled && it.canonicalUrl !in judged }
                .forEach { appendLine(listOf(q.id, it.canonicalUrl, "", q.text, "", "vector distance ${fmt(it.distance)}").joinToString("\t")) }
        }
    }

    /** TREC run file (`qid Q0 docid rank score tag`) for ad-hoc analysis with ranx or trec_eval. */
    fun trecRun(tag: String, without: Set<String> = emptySet()): String = buildString {
        for (q in recorded) {
            ranked(q.id, without).take(100).forEachIndexed { i, r ->
                appendLine("${q.id} Q0 ${r.canonicalUrl.value} ${i + 1} ${"%.6f".format(Locale.ROOT, r.score)} $tag")
            }
        }
    }

    /** The judgements as a TREC qrels file (`qid 0 docid grade`). */
    fun trecQrels(): String = buildString {
        qrels.grades.forEach { (q, m) -> m.forEach { (u, g) -> appendLine("$q 0 $u $g") } }
    }

    /** Markdown report: baseline metrics overall and per group, then one row per left-out source. */
    fun report(secondJudge: Qrels? = null): String = buildString {
        val base = scores()
        appendLine("# Search evaluation (offline replay)\n")
        appendLine("Queries recorded: ${recorded.size} / ${queries.size}; judged: ${base.count { it.ndcg10 != null }}.")
        if (secondJudge != null) {
            val pairs = secondJudge.grades.flatMap { (q, m) -> m.mapNotNull { (u, g) -> qrels.of(q)[u]?.let { it to g } } }
            appendLine("Second judge: ${pairs.size} common judgements, Cohen's kappa ${fmt(RelevanceMetrics.cohensKappa(pairs))}.")
        }
        appendLine()
        appendLine("| group | queries | nDCG@10 | MRR | Recall@20 | dup@10 | unjudged@10 |")
        appendLine("|---|---|---|---|---|---|---|")
        appendLine(row("all", base))
        recorded.groupBy { it.group }.forEach { (g, qs) -> appendLine(row(g, base.filter { s -> qs.any { it.id == s.queryId } })) }
        appendLine("\n## Ablation: each source left out\n")
        appendLine("Marginal Recall@20 = Recall@20 with every source minus Recall@20 without this one. " +
            "ΔnDCG@10 = without minus with; 95 % CI by paired bootstrap over judged queries.\n")
        appendLine("| source | in top 10 (queries) | marginal Recall@20 | ΔnDCG@10 | 95 % CI | p50 latency ms |")
        appendLine("|---|---|---|---|---|---|")
        for (id in sourceIds) {
            val ablated = scores(setOf(id))
            val pairs = base.zip(ablated).filter { (a, b) -> a.ndcg10 != null && b.ndcg10 != null }
            val ci = pairs.takeIf { it.isNotEmpty() }?.let { p -> RelevanceMetrics.pairedBootstrap(p.map { it.first.ndcg10!! }, p.map { it.second.ndcg10!! }) }
            val inTop = recorded.count { q -> ranked(q.id).take(10).any { r -> r.hits.any { it.sourceId == id } } }
            val marginal = mean(base.mapNotNull { it.recall20 }) - mean(ablated.mapNotNull { it.recall20 })
            val latencies = snapshots.values.mapNotNull { s -> s.sources.firstOrNull { it.id == id }?.latencyMs }.sorted()
            appendLine("| $id | $inTop | ${fmt(marginal)} | ${fmt(ci?.meanDifference)} | " +
                "${ci?.let { "${fmt(it.low)} … ${fmt(it.high)}" } ?: "–"} | ${latencies.getOrNull(latencies.size / 2) ?: "–"} |")
        }
    }

    private fun row(name: String, s: List<QueryScore>) =
        "| $name | ${s.size} | ${fmt(meanOrNull(s.mapNotNull { it.ndcg10 }))} | ${fmt(meanOrNull(s.mapNotNull { it.mrr }))} | " +
            "${fmt(meanOrNull(s.mapNotNull { it.recall20 }))} | ${fmt(meanOrNull(s.map { it.duplicates10.toDouble() }))} | " +
            "${fmt(meanOrNull(s.map { it.unjudged10 }))} |"

    companion object {
        fun urlsOf(r: MergedResult): Set<String> =
            (listOf(r.canonicalUrl.value) + r.hits.flatMap { listOfNotNull(it.canonicalUrl.value, it.repositoryUrl?.value) }).toSet()

        fun mean(xs: List<Double>) = meanOrNull(xs) ?: 0.0
        fun meanOrNull(xs: List<Double>) = if (xs.isEmpty()) null else xs.average()
        fun fmt(x: Double?) = x?.let { "%.3f".format(Locale.ROOT, it) } ?: "–"
    }
}
