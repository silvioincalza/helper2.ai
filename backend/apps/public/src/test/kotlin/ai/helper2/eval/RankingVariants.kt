package ai.helper2.eval

import ai.helper2.search.Ranking
import ai.helper2.search.RankingWeights

data class RankingVariant(val slug: String, val name: String, val replay: SearchReplay)

object RankingVariants {
  private fun weighted(slug: String, name: String, weights: RankingWeights) =
    RankingVariant(slug, name, SearchReplay(ranking = Ranking(weights = weights)))

  val all: List<RankingVariant> =
    listOf(
      weighted("exact-title", "exact title +0.05", RankingWeights(exactTitle = 0.05)),
      weighted(
        "rescale",
        "titles +0.005 exact / +0.002 every word, popularity 0.002",
        RankingWeights(exactTitle = 0.005, title = 0.002, popularity = 0.002),
      ),
      weighted(
        "exact-kept",
        "exact title kept, every word +0.002, popularity 0.002",
        RankingWeights(title = 0.002, popularity = 0.002),
      ),
      weighted(
        "every-word-low",
        "exact title kept, every word +0.002",
        RankingWeights(title = 0.002),
      ),
      weighted(
        "path-halved",
        "audience, path type and type hint halved",
        RankingWeights(audience = 0.005, pathType = 0.0025, typeHint = 0.0025),
      ),
      weighted("popularity-quarter", "popularity 0.002", RankingWeights(popularity = 0.002)),
      weighted("scores-only", "no lane brought to the top", RankingWeights(laneWindow = 0)),
      weighted(
        "unmaintained-kept",
        "an unmaintained result keeps its score",
        RankingWeights(unmaintained = 0.0),
      ),
      RankingVariant(
        "one-catalog-vote",
        "one vote for index, semantic and curated (best rank)",
        SearchReplay(oneCatalogVote = true),
      ),
      RankingVariant(
        "echo-no-vote",
        "a catalog hit of a result a live source found adds no vote",
        SearchReplay(echoNoVote = true),
      ),
      RankingVariant(
        "news-last",
        "news after the other results unless the question asks for news",
        SearchReplay(newsLast = true),
      ),
    )
}
