package ai.helper2.eval

import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Offline relevance metrics over graded judgements (0 not relevant, 1 marginal, 2 relevant, 3 the best answer).
 * A run is the list of grades in rank order; null marks a result no judge has graded (it counts as 0).
 */
object RelevanceMetrics {

    /** Grade from which a result counts as relevant for MRR and recall. */
    const val RELEVANT = 2

    /** Discounted cumulative gain with gain 2^grade - 1 and discount log2(position + 1). */
    fun dcg(grades: List<Int?>, k: Int): Double =
        grades.take(k).withIndex().sumOf { (i, g) -> (2.0.pow(g ?: 0) - 1) / log2(i + 2.0) }

    /** DCG@k over the ideal order of every judged grade of the query; null when no grade is above 0. */
    fun ndcg(grades: List<Int?>, judged: Collection<Int>, k: Int): Double? {
        val ideal = dcg(judged.sortedDescending(), k)
        return if (ideal == 0.0) null else dcg(grades, k) / ideal
    }

    fun reciprocalRank(grades: List<Int?>): Double =
        grades.indexOfFirst { (it ?: 0) >= RELEVANT }.let { if (it < 0) 0.0 else 1.0 / (it + 1) }

    /** Share of [relevant] resources in [found]; null when the query has no relevant resource. */
    fun recall(found: Set<String>, relevant: Set<String>): Double? =
        if (relevant.isEmpty()) null else found.count { it in relevant }.toDouble() / relevant.size

    /**
     * Results in the first [k] whose name repeats the name of a higher result: the same project under two URLs
     * (an npm package and its repository, a model and its Space). Name = last path segment of the title,
     * lower-cased, letters and digits only.
     */
    fun duplicates(titles: List<String>, k: Int): Int {
        val seen = HashSet<String>()
        return titles.take(k).count { !seen.add(nameKey(it)) }
    }

    fun unjudgedShare(grades: List<Int?>, k: Int): Double {
        val top = grades.take(k)
        return if (top.isEmpty()) 0.0 else top.count { it == null }.toDouble() / top.size
    }

    data class Interval(val meanDifference: Double, val low: Double, val high: Double) {
        val excludesZero: Boolean get() = low > 0 || high < 0
    }

    /**
     * 95 % confidence interval of mean(b - a) by the paired bootstrap: queries are resampled with replacement,
     * the same query for both runs. Fixed seed, so a report is repeatable.
     */
    fun pairedBootstrap(a: List<Double>, b: List<Double>, resamples: Int = 10_000, seed: Long = 20261030): Interval {
        require(a.size == b.size && a.isNotEmpty()) { "both runs need the same, non-empty list of queries" }
        val diffs = a.indices.map { b[it] - a[it] }
        val random = Random(seed)
        val means = DoubleArray(resamples) { diffs.indices.sumOf { diffs[random.nextInt(diffs.size)] } / diffs.size }
        means.sort()
        fun at(q: Double) = means[((resamples - 1) * q).toInt()]
        return Interval(diffs.average(), at(0.025), at(0.975))
    }

    /**
     * Cohen's kappa of two judges over the same items: (observed agreement - chance agreement) / (1 - chance agreement),
     * chance from each judge's own grade frequencies. Null with no common item or when chance agreement is 1.
     */
    fun cohensKappa(pairs: List<Pair<Int, Int>>): Double? {
        if (pairs.isEmpty()) return null
        val n = pairs.size.toDouble()
        val observed = pairs.count { it.first == it.second } / n
        val first = pairs.groupingBy { it.first }.eachCount()
        val second = pairs.groupingBy { it.second }.eachCount()
        val chance = first.entries.sumOf { (grade, count) -> count / n * (second[grade] ?: 0) / n }
        return if (chance == 1.0) null else (observed - chance) / (1 - chance)
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun nameKey(title: String) = title.substringAfterLast('/').lowercase().filter { it.isLetterOrDigit() }
}
