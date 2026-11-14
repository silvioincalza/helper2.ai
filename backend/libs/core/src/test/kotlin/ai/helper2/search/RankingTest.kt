package ai.helper2.search

import ai.helper2.shared.Audience
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals

class RankingTest {

  private val merger = ResultMerger()
  private val ranking = Ranking()
  private val scoresOnly = Ranking(weights = RankingWeights(laneWindow = 0))

  @Test
  fun `result found by several sources near the top outranks a single deep hit`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/deep", rank = 15),
          hit("github", "https://github.com/a/both", rank = 1),
          hit("npm", "https://github.com/a/both", rank = 0),
        ),
        contexts("github", "npm"),
      )
    val ranked = ranking.rank(merged, "something", null)
    assertEquals("https://github.com/a/both", ranked.first().canonicalUrl.value)
  }

  private fun stars(n: Long) =
    listOf(PopularityMetric(PopularityKind.GITHUB_STARS, n, "github", NOW))

  @Test
  fun `popularity reorders near ties`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/tiny", rank = 0),
          hit("github", "https://github.com/a/known", rank = 2).copy(popularity = stars(3_400)),
        ),
        contexts("github"),
      )
    assertEquals(
      "https://github.com/a/known",
      ranking.rank(merged, "something", null).first().canonicalUrl.value,
    )
  }

  @Test
  fun `popularity never beats a title that matches the query`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/famous", title = "Famous thing", rank = 0)
            .copy(popularity = stars(200_000)),
          hit("github", "https://github.com/a/pg-mcp", title = "pg-mcp", rank = 5),
        ),
        contexts("github"),
      )
    assertEquals("pg-mcp", ranking.rank(merged, "pg mcp", null).first().title)
  }

  @Test
  fun `popularity uses the strongest metric, log-scaled and capped, never a sum`() {
    assertEquals(0.0, Ranking.popularity(emptyList()))
    val few =
      Ranking.popularity(listOf(PopularityMetric(PopularityKind.GITHUB_STARS, 24, "github", NOW)))
    val many =
      Ranking.popularity(
        listOf(PopularityMetric(PopularityKind.GITHUB_STARS, 3_400, "github", NOW))
      )
    val capped =
      Ranking.popularity(
        listOf(PopularityMetric(PopularityKind.GITHUB_STARS, 10_000_000, "github", NOW))
      )
    val both =
      Ranking.popularity(
        listOf(
          PopularityMetric(PopularityKind.GITHUB_STARS, 3_400, "github", NOW),
          PopularityMetric(PopularityKind.NPM_DOWNLOADS_WEEKLY, 10, "npm", NOW),
        )
      )
    kotlin.test.assertTrue(few < many && many < 1.0)
    assertEquals(1.0, capped)
    assertEquals(many, both)
  }

  @Test
  fun `a type named in the query boosts results of that type`() {
    val merged =
      merger.merge(
        listOf(
          hit(
            "github",
            "https://github.com/a/lib",
            rank = 0,
            type = ai.helper2.shared.ResourceType.LIBRARY,
          ),
          hit(
            "github",
            "https://github.com/a/server",
            rank = 0,
            type = ai.helper2.shared.ResourceType.MCP_SERVER,
          ),
        ),
        contexts("github"),
      )
    val ranked =
      ranking.rank(merged, "postgres", null, setOf(ai.helper2.shared.ResourceType.MCP_SERVER))
    assertEquals("https://github.com/a/server", ranked.first().canonicalUrl.value)
  }

  @Test
  fun `title boost ignores type words when the caller passes the core words`() {
    val merged =
      merger.merge(
        listOf(
          hit(
            "github",
            "https://github.com/a/mcp-postgres-server",
            title = "a/mcp-postgres-server",
            rank = 0,
          ),
          hit("github", "https://github.com/b/postgres-mcp", title = "b/postgres-mcp", rank = 0)
            .copy(popularity = stars(3_400)),
        ),
        contexts("github"),
      )
    val ranked =
      ranking.rank(merged, "MCP server Postgres", null, emptySet(), listOf("mcp", "postgres"))
    assertEquals("b/postgres-mcp", ranked.first().title)
  }

  @Test
  fun `title boost needs whole words, not letters inside another word`() {
    val merged =
      merger.merge(
        listOf(
          hit(
            "github",
            "https://github.com/a/leverage-tools",
            title = "a/leverage-tools",
            rank = 0,
          ),
          hit("github", "https://github.com/b/rag-tools", title = "b/rag-tools", rank = 1),
          hit("github", "https://github.com/c/email-ai", title = "c/email-sender", rank = 2),
        ),
        contexts("github"),
      )

    val ranked = ranking.rank(merged, "rag", null)

    assertEquals(
      listOf("b/rag-tools", "a/leverage-tools", "c/email-sender"),
      ranked.map { it.title },
    )
  }

  @Test
  fun `a short word is not another form of a much longer one`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/datadog-agent", title = "a/datadog-agent", rank = 0),
          hit("github", "https://github.com/b/data-agent", title = "b/data-agent", rank = 1),
        ),
        contexts("github"),
      )

    val ranked = ranking.rank(merged, "x", null, emptySet(), listOf("data agent"))

    assertEquals("b/data-agent", ranked.first().title)
  }

  @Test
  fun `title boost accepts another form of a long word and ignores small words`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/other", title = "a/other", rank = 0),
          hit(
            "github",
            "https://github.com/b/agent-translator",
            title = "b/agent-translator",
            rank = 1,
          ),
        ),
        contexts("github"),
      )

    val ranked = ranking.rank(merged, "x", null, emptySet(), listOf("agents for translator"))

    assertEquals("b/agent-translator", ranked.first().title)
  }

  @Test
  fun `exact title match is boosted`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/other", title = "Other thing", rank = 0),
          hit("github", "https://github.com/a/ollama", title = "Ollama", rank = 3),
        ),
        contexts("github"),
      )
    assertEquals("Ollama", ranking.rank(merged, "ollama", null).first().title)
  }

  @Test
  fun `an exact title gets the same boost as a title that holds every word`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/exact", title = "Ollama", rank = 1),
          hit("github", "https://github.com/a/words", title = "Ollama UI", rank = 0),
        ),
        contexts("github"),
      )
    assertEquals("Ollama UI", ranking.rank(merged, "ollama", null).first().title)
  }

  @Test
  fun `a title with every word beats one more source`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/two", title = "Something else", rank = 0),
          hit("npm", "https://github.com/a/two", title = "Something else", rank = 0),
          hit("github", "https://github.com/a/named", title = "Ollama UI", rank = 1),
        ),
        contexts("github", "npm"),
      )
    assertEquals("Ollama UI", ranking.rank(merged, "ollama", null).first().title)
  }

  @Test
  fun `a matching audience is worth less than one more top-ranked source`() {
    val merged =
      merger
        .merge(
          listOf(
            hit("github", "https://github.com/a/two", rank = 0),
            hit("npm", "https://github.com/a/two", rank = 0),
            hit("hf-spaces", "https://github.com/a/app", rank = 0),
          ),
          contexts("github", "npm", "hf-spaces"),
        )
        .map {
          if (it.title == "app") it.copy(audience = Audience.USE_AI)
          else it.copy(audience = Audience.BUILD_WITH_AI)
        }
    assertEquals("two", scoresOnly.rank(merged, "x", AudiencePath.USE_AI).first().title)
  }

  @Test
  fun `the strongest popularity is worth less than one more source at any rank`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/two", rank = 0),
          hit("npm", "https://github.com/a/two", rank = 29),
          hit("github", "https://github.com/a/famous", rank = 1)
            .copy(popularity = stars(10_000_000)),
        ),
        contexts("github", "npm"),
      )
    assertEquals("two", ranking.rank(merged, "x", null).first().title)
  }

  @Test
  fun `path boosts matching audience without removing others`() {
    val base =
      merger
        .merge(
          listOf(
            hit("github", "https://github.com/a/dev", rank = 0),
            hit("github", "https://github.com/a/app", rank = 1),
          ),
          contexts("github"),
        )
        .map {
          if (it.title == "app") it.copy(audience = Audience.USE_AI)
          else it.copy(audience = Audience.BUILD_WITH_AI)
        }

    val useAi = ranking.rank(base, "x", AudiencePath.USE_AI)
    val build = ranking.rank(base, "x", AudiencePath.BUILD_WITH_AI)
    assertEquals("app", useAi.first().title)
    assertEquals("dev", build.first().title)
    assertEquals(2, useAi.size)
  }

  private fun needResults(vararg typesByRank: ResourceType) =
    merger.merge(
      typesByRank.mapIndexed { i, type ->
        hit("github", "https://example.org/r$i", title = "r$i", rank = i, type = type)
      },
      contexts("github"),
    )

  private fun titles(results: List<MergedResult>) = results.map { it.title }

  @Test
  fun `the best result of the other lane takes the second place`() {
    val merged =
      needResults(
        ResourceType.LIBRARY,
        ResourceType.LIBRARY,
        ResourceType.MODEL,
        ResourceType.PRODUCT,
        ResourceType.PRODUCT,
      )

    assertEquals(listOf("r0", "r3", "r1", "r2", "r4"), titles(ranking.rank(merged, "a need", null)))
  }

  @Test
  fun `an app on top brings the best thing to build with to the second place`() {
    val merged = needResults(ResourceType.PRODUCT, ResourceType.PRODUCT, ResourceType.MCP_SERVER)

    assertEquals(listOf("r0", "r2", "r1"), titles(ranking.rank(merged, "a need", null)))
  }

  @Test
  fun `a result outside the first ten is not brought up`() {
    val merged = needResults(*Array(10) { ResourceType.LIBRARY }, ResourceType.PRODUCT)

    assertEquals("r1", ranking.rank(merged, "a need", null)[1].title)
  }

  @Test
  fun `with a chosen path its best result takes the first place`() {
    val merged = needResults(ResourceType.LIBRARY, ResourceType.LIBRARY, ResourceType.PRODUCT)

    assertEquals(
      listOf("r2", "r0", "r1"),
      titles(ranking.rank(merged, "a need", AudiencePath.USE_AI)),
    )
    assertEquals(
      listOf("r0", "r1", "r2"),
      titles(ranking.rank(merged, "a need", AudiencePath.BUILD_WITH_AI)),
    )
  }

  @Test
  fun `a stated audience decides the lane before the type`() {
    val merged =
      needResults(ResourceType.LIBRARY, ResourceType.LIBRARY, ResourceType.PRODUCT).map {
        if (it.title == "r1") it.copy(audience = Audience.USE_AI) else it
      }

    assertEquals(listOf("r0", "r1", "r2"), titles(ranking.rank(merged, "a need", null)))
  }

  @Test
  fun `a query that names a result keeps the order of the scores`() {
    val merged = needResults(ResourceType.LIBRARY, ResourceType.LIBRARY, ResourceType.PRODUCT)

    assertEquals(listOf("r1", "r0", "r2"), titles(ranking.rank(merged, "r1", null)))
  }

  @Test
  fun `a first result whose title holds every word of the query keeps the second place as it is`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://example.org/a", title = "acme/ollama", rank = 0),
          hit("github", "https://example.org/b", title = "ollama-js", rank = 1),
          hit(
            "github",
            "https://example.org/c",
            title = "chat app",
            rank = 2,
            type = ResourceType.PRODUCT,
          ),
        ),
        contexts("github"),
      )

    assertEquals(
      listOf("acme/ollama", "ollama-js", "chat app"),
      titles(ranking.rank(merged, "ollama", null)),
    )
  }

  @Test
  fun `a query that names a type keeps the order of the scores`() {
    val merged = needResults(ResourceType.LIBRARY, ResourceType.LIBRARY, ResourceType.PRODUCT)

    assertEquals(
      listOf("r0", "r1", "r2"),
      titles(ranking.rank(merged, "a need", null, setOf(ResourceType.MODEL))),
    )
  }

  @Test
  fun `news or a paper on top moves nothing`() {
    val merged = needResults(ResourceType.NEWS, ResourceType.LIBRARY, ResourceType.PRODUCT)

    assertEquals(listOf("r0", "r1", "r2"), titles(ranking.rank(merged, "a need", null)))
  }

  @Test
  fun `a lane window of zero keeps the order of the scores`() {
    val merged = needResults(ResourceType.LIBRARY, ResourceType.LIBRARY, ResourceType.PRODUCT)

    assertEquals(listOf("r0", "r1", "r2"), titles(scoresOnly.rank(merged, "a need", null)))
  }

  @Test
  fun `an unmaintained library ranks below a maintained one found at the next place`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/old", rank = 0, updated = "2020-01-01T00:00:00Z"),
          hit("github", "https://github.com/a/alive", rank = 1, updated = NOW.toString()),
        ),
        contexts("github"),
      )

    assertEquals(listOf("alive", "old"), titles(ranking.rank(merged, "a need", null)))
  }

  @Test
  fun `an unmaintained library still ranks above one that one source fewer found`() {
    val merged =
      merger.merge(
        listOf(
          hit("github", "https://github.com/a/old", rank = 0, updated = "2020-01-01T00:00:00Z"),
          hit("npm", "https://github.com/a/old", rank = 0),
          hit("github", "https://github.com/a/alive", rank = 1, updated = NOW.toString()),
        ),
        contexts("github", "npm"),
      )

    assertEquals(listOf("old", "alive"), titles(ranking.rank(merged, "a need", null)))
  }

  @Test
  fun `ranking is deterministic for ties`() {
    val merged =
      merger.merge(
        listOf(
          hit("a", "https://github.com/a/b1", rank = 0),
          hit("b", "https://github.com/a/b2", rank = 0),
        ),
        contexts("a", "b"),
      )
    assertEquals(
      ranking.rank(merged, "q", null).map { it.id },
      ranking.rank(merged.reversed(), "q", null).map { it.id },
    )
  }
}
