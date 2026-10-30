package ai.helper2.eval

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue

/**
 * Offline search evaluation (ADR 0036): `./gradlew evalTest`. Replays the recorded snapshots through production merge
 * and ranking, writes the report, the judging pool and TREC files to backend/eval-data, and gates nDCG@10 against
 * docs/eval/search-baseline.json. Skipped, not failed, while snapshots or judgements are missing.
 */
@Tag("eval")
class SearchRelevanceEvalTest {

    private val queries = EvalFiles.searchQueries() + EvalFiles.semanticQueries()
    private val snapshots = EvalFiles.readSnapshots()
    private val qrels = EvalFiles.qrels()
    private val evaluation = SearchEvaluation(queries, snapshots, qrels)

    @Test
    fun `writes the report, the judging pool and TREC files`() {
        assumeTrue(snapshots.isNotEmpty(), "No snapshots in ${EvalFiles.snapshots}: run ./gradlew evalRecord first.")
        val written = listOf(
            EvalFiles.write("search-report.md", evaluation.report(EvalFiles.secondJudge()) + "\n## Semantic cutoff sweep\n\n" +
                CutoffSweep.table(CutoffSweep.sweep(snapshots.values, qrels, controlIds()))),
            EvalFiles.write("search-pool.tsv", evaluation.pool()),
            EvalFiles.write("baseline.run", evaluation.trecRun("baseline")),
            EvalFiles.write("search.qrels", evaluation.trecQrels()),
        ) + evaluation.sourceIds.map { id -> EvalFiles.write("without-$id.run", evaluation.trecRun("without-$id", setOf(id))) }
        written.forEach { println("wrote $it") }
        assertTrue(written.all(File::isFile))
    }

    @Test
    fun `nDCG at 10 does not fall below the baseline`() {
        assumeTrue(snapshots.isNotEmpty(), "No snapshots in ${EvalFiles.snapshots}: run ./gradlew evalRecord first.")
        assumeTrue(!qrels.isEmpty, "docs/eval/search-qrels.tsv has no judgements yet: label backend/eval-data/search-pool.tsv first.")
        val measured = SearchEvaluation.mean(evaluation.scores().mapNotNull { it.ndcg10 })
        val current = SearchBaseline(snapshotsFingerprint(), qrels.fingerprint(), measured, SearchBaseline.TOLERANCE)
        val file = File(EvalFiles.docs, "search-baseline.json")
        if (System.getenv("H2_EVAL_WRITE_BASELINE") == "true") {
            EvalFiles.json.writerWithDefaultPrettyPrinter().writeValue(file, current)
            println("baseline nDCG@10 ${SearchEvaluation.fmt(measured)} written to $file")
            return
        }
        assumeTrue(file.isFile, "No $file: run once with H2_EVAL_WRITE_BASELINE=true to store the baseline.")
        val baseline = EvalFiles.json.readValue<SearchBaseline>(file)
        assumeTrue(baseline.snapshots == current.snapshots && baseline.qrels == current.qrels,
            "The baseline was measured on other snapshots or judgements: store a new one with H2_EVAL_WRITE_BASELINE=true.")
        println("nDCG@10 ${SearchEvaluation.fmt(measured)}, baseline ${SearchEvaluation.fmt(baseline.ndcg10)}")
        assertTrue(measured >= baseline.ndcg10 - baseline.tolerance,
            "nDCG@10 fell from ${SearchEvaluation.fmt(baseline.ndcg10)} to ${SearchEvaluation.fmt(measured)} " +
                "(tolerance ${baseline.tolerance}); see backend/eval-data/search-report.md")
    }

    private fun controlIds() = queries.filter { it.group == "nonsense" }.map { it.id }.toSet()

    private fun snapshotsFingerprint() =
        EvalFiles.sha256(snapshots.values.joinToString("\n") { "${it.queryId}\t${it.recordedAt}" })
}

/** The gate's reference: nDCG@10 measured on these snapshots (fingerprint) with these judgements (fingerprint). */
data class SearchBaseline(val snapshots: String, val qrels: String, val ndcg10: Double, val tolerance: Double) {
    companion object {
        /** Absolute nDCG@10 drop the gate accepts: below the noise of 80 queries, above float rounding. */
        const val TOLERANCE = 0.005
    }
}
