package ai.helper2.eval

import ai.helper2.search.MergedResult
import java.util.Locale

data class QueryScore(
  val queryId: String,
  val ndcg10: Double?,
  val mrr: Double?,
  val recall20: Double?,
  val duplicates10: Int,
  val unjudged10: Double,
)

class SearchEvaluation(
  private val queries: List<EvalQuery>,
  private val snapshots: Map<String, SearchSnapshot>,
  private val qrels: Qrels,
  private val replay: SearchReplay = SearchReplay(),
  private val variants: List<RankingVariant> = emptyList(),
) {
  val recorded: List<EvalQuery> = queries.filter { it.id in snapshots }
  val sourceIds: List<String> =
    snapshots.values.flatMap { s -> s.sources.map { it.id } }.distinct().sorted()

  fun ranked(
    queryId: String,
    without: Set<String> = emptySet(),
    by: SearchReplay = replay,
  ): List<MergedResult> = by.rank(snapshots.getValue(queryId), without)

  fun grade(queryId: String, r: MergedResult): Int? {
    val judged = qrels.of(queryId)
    return urlsOf(r).mapNotNull { judged[it] }.maxOrNull()
  }

  fun score(
    queryId: String,
    without: Set<String> = emptySet(),
    by: SearchReplay = replay,
  ): QueryScore {
    val results = ranked(queryId, without, by)
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

  fun scores(without: Set<String> = emptySet(), by: SearchReplay = replay): List<QueryScore> =
    recorded.map {
      score(it.id, without, by)
    }

  fun pool(): String = buildString {
    appendLine("query_id\turl\tgrade\tquery\ttitle\tsources")
    for (q in recorded) {
      val runs =
        (listOf(emptySet<String>()) + sourceIds.map { setOf(it) }).map { ranked(q.id, it) } +
          variants.map { ranked(q.id, by = it.replay) }
      val seen = mutableListOf<MergedResult>()
      val covered = HashSet<String>()
      runs.forEach { run ->
        run.take(20).forEach { r ->
          urlsOf(r).let { urls ->
            if (covered.none(urls::contains)) seen += r
            covered += urls
          }
        }
      }
      val judged = qrels.of(q.id)
      seen
        .filter { grade(q.id, it) == null }
        .forEach { r ->
          appendLine(
            listOf(
                q.id,
                r.canonicalUrl.value,
                "",
                q.text,
                r.title.replace('\t', ' '),
                r.sources.joinToString(",") { it.sourceId },
              )
              .joinToString("\t")
          )
        }
      snapshots
        .getValue(q.id)
        .semantic
        .orEmpty()
        .take(10)
        .filter { it.canonicalUrl !in covered && it.canonicalUrl !in judged }
        .forEach {
          appendLine(
            listOf(q.id, it.canonicalUrl, "", q.text, "", "vector distance ${fmt(it.distance)}")
              .joinToString("\t")
          )
        }
    }
  }

  fun newInTop10(variant: RankingVariant): String = buildString {
    appendLine("query_id\turl\tgrade\tquery\ttitle\tsources")
    for (q in recorded.filter { qrels.of(it.id).isNotEmpty() }) {
      val before = ranked(q.id).take(10).flatMap(::urlsOf).toSet()
      ranked(q.id, by = variant.replay)
        .take(10)
        .filter { r -> grade(q.id, r) == null && urlsOf(r).none(before::contains) }
        .forEach { r ->
          appendLine(
            listOf(
                q.id,
                r.canonicalUrl.value,
                "",
                q.text,
                r.title.replace('\t', ' '),
                r.sources.joinToString(",") { it.sourceId },
              )
              .joinToString("\t")
          )
        }
    }
  }

  fun trecRun(tag: String, without: Set<String> = emptySet()): String = buildString {
    for (q in recorded) {
      val run = ranked(q.id, without).take(TREC_DEPTH)
      run.forEachIndexed { i, r ->
        appendLine("${q.id} Q0 ${r.canonicalUrl.value} ${i + 1} ${run.size - i} $tag")
      }
    }
  }

  fun trecQrels(): String = buildString {
    qrels.grades.forEach { (q, m) -> m.forEach { (u, g) -> appendLine("$q 0 $u $g") } }
  }

  fun report(secondJudge: Qrels? = null): String = buildString {
    val base = scores()
    appendLine("# Search evaluation (offline replay)\n")
    appendLine(
      "Queries recorded: ${recorded.size} / ${queries.size}; judged: ${base.count { it.ndcg10 != null }}."
    )
    if (secondJudge != null) {
      val pairs =
        secondJudge.grades.flatMap { (q, m) ->
          m.mapNotNull { (u, g) -> qrels.of(q)[u]?.let { it to g } }
        }
      appendLine(
        "Second judge: ${pairs.size} common judgements, Cohen's kappa ${fmt(RelevanceMetrics.cohensKappa(pairs))}."
      )
    }
    appendLine()
    appendLine("| group | queries | nDCG@10 | MRR | Recall@20 | dup@10 | unjudged@10 |")
    appendLine("|---|---|---|---|---|---|---|")
    appendLine(row("all", base))
    recorded
      .groupBy { it.group }
      .forEach { (g, qs) -> appendLine(row(g, base.filter { s -> qs.any { it.id == s.queryId } })) }
    append(homeExamples())
    append(variantsTable(base))
    appendLine("\n## Ablation: each source left out\n")
    appendLine(
      "Marginal Recall@20 = Recall@20 with every source minus Recall@20 without this one. " +
        "ΔnDCG@10 = without minus with; 95 % CI by paired bootstrap over judged queries.\n"
    )
    appendLine(
      "| source | in top 10 (queries) | marginal Recall@20 | ΔnDCG@10 | 95 % CI | p50 latency ms |"
    )
    appendLine("|---|---|---|---|---|---|")
    for (id in sourceIds) {
      val ablated = scores(setOf(id))
      val pairs = base.zip(ablated).filter { (a, b) -> a.ndcg10 != null && b.ndcg10 != null }
      val ci =
        pairs
          .takeIf { it.isNotEmpty() }
          ?.let { p ->
            RelevanceMetrics.pairedBootstrap(
              p.map { it.first.ndcg10!! },
              p.map { it.second.ndcg10!! },
            )
          }
      val inTop = recorded.count { q ->
        ranked(q.id).take(10).any { r -> r.hits.any { it.sourceId == id } }
      }
      val marginal =
        mean(base.mapNotNull { it.recall20 }) - mean(ablated.mapNotNull { it.recall20 })
      val latencies =
        snapshots.values
          .mapNotNull { s -> s.sources.firstOrNull { it.id == id }?.latencyMs }
          .sorted()
      appendLine(
        "| $id | $inTop | ${fmt(marginal)} | ${fmt(ci?.meanDifference)} | " +
          "${ci?.let { "${fmt(it.low)} … ${fmt(it.high)}" } ?: "–"} | ${latencies.getOrNull(latencies.size / 2) ?: "–"} |"
      )
    }
  }

  private fun variantsTable(base: List<QueryScore>): String = buildString {
    if (variants.isEmpty()) return@buildString
    appendLine("\n## Ranking variants\n")
    appendLine(
      "ΔnDCG@10 = variant minus the code's weights; 95 % CI by paired bootstrap over judged queries. " +
        "Groups lists every group whose mean moved.\n"
    )
    appendLine("| variant | ΔnDCG@10 | 95 % CI | unjudged@10 | home examples kept | groups |")
    appendLine("|---|---|---|---|---|---|")
    for (v in variants) {
      val other = scores(by = v.replay)
      val ci = interval(base, other)
      val kept =
        recorded
          .filter { it.group == HOME_EXAMPLE }
          .count { q -> (bestGradeInTop3(q.id, v.replay) ?: 0) >= RelevanceMetrics.RELEVANT }
      val groups =
        recorded
          .groupBy { it.group }
          .mapNotNull { (g, qs) ->
            val ids = qs.map { it.id }.toSet()
            interval(base.filter { it.queryId in ids }, other.filter { it.queryId in ids })
              ?.takeIf { it.meanDifference != 0.0 }
              ?.let { "$g ${fmt(it.meanDifference)} (${fmt(it.low)} … ${fmt(it.high)})" }
          }
          .joinToString("; ")
          .ifEmpty { "–" }
      appendLine(
        "| ${v.name} | ${fmt(ci?.meanDifference)} | ${ci?.let { "${fmt(it.low)} … ${fmt(it.high)}" } ?: "–"} | " +
          "${fmt(mean(other.map { it.unjudged10 }))} | $kept | $groups |"
      )
    }
  }

  private fun interval(a: List<QueryScore>, b: List<QueryScore>): RelevanceMetrics.Interval? {
    val pairs = a.zip(b).filter { (x, y) -> x.ndcg10 != null && y.ndcg10 != null }
    if (pairs.isEmpty()) return null
    return RelevanceMetrics.pairedBootstrap(
      pairs.map { it.first.ndcg10!! },
      pairs.map { it.second.ndcg10!! },
    )
  }

  fun homeExamples(): String = buildString {
    val examples = recorded.filter { it.group == HOME_EXAMPLE }
    if (examples.isEmpty()) return@buildString
    appendLine("\n## Home examples: best grade in the top 3\n")
    appendLine("| query | words | path | best grade in top 3 | keeps its promise |")
    appendLine("|---|---|---|---|---|")
    for (q in examples) {
      val best = bestGradeInTop3(q.id)
      val keeps = best != null && best >= RelevanceMetrics.RELEVANT
      appendLine(
        "| ${q.id} | ${q.text} | ${q.path ?: "–"} | ${best ?: "–"} | ${if (keeps) "yes" else "**no**"} |"
      )
    }
  }

  fun bestGradeInTop3(queryId: String, by: SearchReplay = replay): Int? =
    ranked(queryId, by = by).take(3).mapNotNull { grade(queryId, it) }.maxOrNull()

  private fun row(name: String, s: List<QueryScore>) =
    "| $name | ${s.size} | ${fmt(meanOrNull(s.mapNotNull { it.ndcg10 }))} | ${fmt(meanOrNull(s.mapNotNull { it.mrr }))} | " +
      "${fmt(meanOrNull(s.mapNotNull { it.recall20 }))} | ${fmt(meanOrNull(s.map { it.duplicates10.toDouble() }))} | " +
      "${fmt(meanOrNull(s.map { it.unjudged10 }))} |"

  companion object {
    const val HOME_EXAMPLE = "home-example"
    private const val TREC_DEPTH = 100

    fun urlsOf(r: MergedResult): Set<String> =
      (listOf(r.canonicalUrl.value) +
          r.hits.flatMap { listOfNotNull(it.canonicalUrl.value, it.repositoryUrl?.value) })
        .toSet()

    fun mean(xs: List<Double>) = meanOrNull(xs) ?: 0.0

    fun meanOrNull(xs: List<Double>) = if (xs.isEmpty()) null else xs.average()

    fun fmt(x: Double?) = x?.let { "%.3f".format(Locale.ROOT, it) } ?: "–"
  }
}
