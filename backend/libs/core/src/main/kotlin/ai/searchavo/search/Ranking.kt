package ai.searchavo.search

import ai.searchavo.shared.Audience
import ai.searchavo.shared.AudiencePath
import ai.searchavo.shared.ResourceId
import ai.searchavo.shared.ResourceType
import ai.searchavo.shared.WordLists
import kotlin.math.ln
import kotlin.math.min

data class RankingWeights(
  val exactTitle: Double = 0.02,
  val title: Double = 0.02,
  val audience: Double = 0.01,
  val pathType: Double = 0.005,
  val typeHint: Double = 0.005,
  val popularity: Double = 0.008,
  val laneWindow: Int = 10,
  val unmaintained: Double = 0.008,
  val rating: Double = 0.0,
)

/** The directory rating of results, from 1 to 5; a result that is not rated yet is no key. */
fun interface ResultRatings {
  fun of(ids: Collection<ResourceId>): Map<ResourceId, Double>

  companion object {
    @JvmField val NONE = ResultRatings { emptyMap() }
  }
}

class Ranking
@JvmOverloads
constructor(
  val k: Int = FUSION_K,
  val weights: RankingWeights = RankingWeights(),
  private val vitality: VitalityPolicy = VitalityPolicy(),
  private val ratings: ResultRatings = ResultRatings.NONE,
) {
  fun rank(
    results: List<MergedResult>,
    query: String,
    path: AudiencePath?,
    typeHints: Set<ResourceType> = emptySet(),
    titleTerms: List<String>? = null,
    votes: (MergedResult) -> List<SourceHit> = MergedResult::hits,
  ): List<MergedResult> {
    val q = normalize(query)
    val terms =
      (titleTerms?.flatMap { normalize(it).split(' ') } ?: q.split(' ')).filter {
        it.length > 1 && it !in STOPWORDS
      }
    val rated = if (weights.rating == 0.0) emptyMap() else ratings.of(results.map { it.id })
    val byScore =
      results
        .map { it.copy(score = score(it, q, terms, path, typeHints, votes(it), rated[it.id])) }
        .sortedWith(
          compareByDescending<MergedResult> { it.score }
            .thenBy { it.title.lowercase() }
            .thenBy { it.id.value }
        )
    val namesAResult = typeHints.isNotEmpty() || byScore.any { normalize(it.title) == q }
    return if (namesAResult) byScore else withLanesOnTop(byScore, path, terms)
  }

  private fun withLanesOnTop(
    byScore: List<MergedResult>,
    path: AudiencePath?,
    terms: List<String>,
  ): List<MergedResult> {
    val first = byScore.firstOrNull() ?: return byScore
    val lane = path ?: otherLaneOf(first, terms) ?: return byScore
    val place = if (path != null) 0 else 1
    val best = byScore.take(weights.laneWindow).indexOfFirst { laneOf(it) == lane }
    return if (best <= place) byScore else moved(byScore, from = best, to = place)
  }

  private fun moved(results: List<MergedResult>, from: Int, to: Int): List<MergedResult> =
    results.toMutableList().apply { add(to, removeAt(from)) }

  private fun otherLaneOf(first: MergedResult, terms: List<String>): AudiencePath? {
    val namedByTheQuery = terms.isNotEmpty() && containsEveryWord(normalize(first.title), terms)
    if (namedByTheQuery) return null
    return when (laneOf(first)) {
      AudiencePath.USE_AI -> AudiencePath.BUILD_WITH_AI
      AudiencePath.BUILD_WITH_AI -> AudiencePath.USE_AI
      null -> null
    }
  }

  private fun score(
    r: MergedResult,
    q: String,
    terms: List<String>,
    path: AudiencePath?,
    typeHints: Set<ResourceType>,
    votes: List<SourceHit>,
    rating: Double?,
  ): Double {
    var s = fusion(votes)
    val title = normalize(r.title)
    if (title == q) s += weights.exactTitle
    else if (terms.isNotEmpty() && containsEveryWord(title, terms)) s += weights.title
    // UC-001 BR-012: the audience path changes the score only; it hides nothing.
    if (path != null) {
      if (r.audience.matches(path)) s += weights.audience
      if (path == AudiencePath.USE_AI && r.type == ResourceType.PRODUCT) s += weights.pathType
      if (path == AudiencePath.BUILD_WITH_AI && r.type in BUILDER_TYPES) s += weights.pathType
    }
    if (r.type in typeHints) s += weights.typeHint
    s += weights.popularity * popularity(r.popularity)
    if (vitality.isUnmaintained(r)) s -= weights.unmaintained
    s += weights.rating * ratingSignal(rating)
    return s
  }

  private fun fusion(votes: List<SourceHit>): Double =
    votes.distinctBy { it.sourceId }.sumOf { 1.0 / (k + it.rank + 1) }

  private fun containsEveryWord(title: String, terms: List<String>): Boolean {
    val words = title.split(' ')
    return terms.all { term -> words.any { sameWord(it, term) } }
  }

  private fun sameWord(word: String, term: String): Boolean {
    if (word == term) return true
    val shorter = minOf(word.length, term.length)
    val longer = maxOf(word.length, term.length)
    return shorter >= MIN_PREFIX &&
      shorter * 4 >= longer * 3 &&
      (word.startsWith(term) || term.startsWith(word))
  }

  private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

  companion object {
    const val FUSION_K = 60

    @JvmField
    val POPULARITY_REFERENCE: Map<PopularityKind, Double> =
      mapOf(
        PopularityKind.GITHUB_STARS to 50_000.0,
        PopularityKind.HF_LIKES to 5_000.0,
        PopularityKind.HF_DOWNLOADS to 5_000_000.0,
        PopularityKind.NPM_DOWNLOADS_WEEKLY to 1_000_000.0,
      )

    internal const val MIN_PREFIX = 4

    /** The rating a result counts with when it is not rated yet: the middle of the scale. */
    const val NEUTRAL_RATING = 3.0

    // UC-001 BR-023: from -1 (rated 1) to +1 (rated 5); a result that is not rated yet counts as
    // the middle of the scale, so it is not ranked lower for it.
    internal fun ratingSignal(rating: Double?): Double =
      ((rating ?: NEUTRAL_RATING).coerceIn(1.0, 5.0) - NEUTRAL_RATING) / 2

    internal fun laneOf(r: MergedResult): AudiencePath? =
      when {
        r.audience == Audience.USE_AI -> AudiencePath.USE_AI
        r.audience == Audience.BUILD_WITH_AI -> AudiencePath.BUILD_WITH_AI
        r.type == ResourceType.PRODUCT -> AudiencePath.USE_AI
        r.type in BUILDER_TYPES -> AudiencePath.BUILD_WITH_AI
        else -> null
      }

    internal val STOPWORDS: Set<String> = WordLists.load("query-words").words("ranking.stop")

    internal fun popularity(metrics: List<PopularityMetric>): Double =
      metrics.maxOfOrNull { m ->
        val ref = POPULARITY_REFERENCE[m.kind] ?: return@maxOfOrNull 0.0
        if (m.value <= 0) 0.0 else min(1.0, ln(1.0 + m.value) / ln(1.0 + ref))
      } ?: 0.0

    internal val BUILDER_TYPES =
      setOf(
        ResourceType.MODEL,
        ResourceType.MCP_SERVER,
        ResourceType.LIBRARY,
        ResourceType.SKILL,
        ResourceType.AGENT,
        ResourceType.PROMPT,
        ResourceType.DATASET,
      )
  }
}
