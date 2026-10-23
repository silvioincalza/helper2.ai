package ai.helper2.search

import ai.helper2.shared.Audience
import ai.helper2.shared.AudiencePath
import kotlin.test.Test
import kotlin.test.assertEquals

class RankingTest {

    private val merger = ResultMerger()
    private val ranking = Ranking()

    @Test
    fun `result found by several sources near the top outranks a single deep hit`() {
        val merged = merger.merge(
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

    private fun stars(n: Long) = listOf(PopularityMetric(PopularityKind.GITHUB_STARS, n, "github", NOW))

    @Test
    fun `popularity reorders near ties`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/tiny", rank = 0),
                hit("github", "https://github.com/a/known", rank = 2).copy(popularity = stars(3_400)),
            ),
            contexts("github"),
        )
        assertEquals("https://github.com/a/known", ranking.rank(merged, "something", null).first().canonicalUrl.value)
    }

    @Test
    fun `popularity never beats a title that matches the query`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/famous", title = "Famous thing", rank = 0).copy(popularity = stars(200_000)),
                hit("github", "https://github.com/a/pg-mcp", title = "pg-mcp", rank = 5),
            ),
            contexts("github"),
        )
        assertEquals("pg-mcp", ranking.rank(merged, "pg mcp", null).first().title)
    }

    @Test
    fun `popularity uses the strongest metric, log-scaled and capped, never a sum`() {
        assertEquals(0.0, Ranking.popularity(emptyList()))
        val few = Ranking.popularity(listOf(PopularityMetric(PopularityKind.GITHUB_STARS, 24, "github", NOW)))
        val many = Ranking.popularity(listOf(PopularityMetric(PopularityKind.GITHUB_STARS, 3_400, "github", NOW)))
        val capped = Ranking.popularity(listOf(PopularityMetric(PopularityKind.GITHUB_STARS, 10_000_000, "github", NOW)))
        val both = Ranking.popularity(listOf(
            PopularityMetric(PopularityKind.GITHUB_STARS, 3_400, "github", NOW),
            PopularityMetric(PopularityKind.NPM_DOWNLOADS_WEEKLY, 10, "npm", NOW),
        ))
        kotlin.test.assertTrue(few < many && many < 1.0)
        assertEquals(1.0, capped)
        assertEquals(many, both)
    }

    @Test
    fun `a type named in the query boosts results of that type`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/lib", rank = 0, type = ai.helper2.shared.ResourceType.LIBRARY),
                hit("github", "https://github.com/a/server", rank = 0, type = ai.helper2.shared.ResourceType.MCP_SERVER),
            ),
            contexts("github"),
        )
        val ranked = ranking.rank(merged, "postgres", null, setOf(ai.helper2.shared.ResourceType.MCP_SERVER))
        assertEquals("https://github.com/a/server", ranked.first().canonicalUrl.value)
    }

    @Test
    fun `title boost ignores type words when the caller passes the core words`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/mcp-postgres-server", title = "a/mcp-postgres-server", rank = 0),
                hit("github", "https://github.com/b/postgres-mcp", title = "b/postgres-mcp", rank = 0)
                    .copy(popularity = stars(3_400)),
            ),
            contexts("github"),
        )
        val ranked = ranking.rank(merged, "MCP server Postgres", null, emptySet(), listOf("mcp", "postgres"))
        assertEquals("b/postgres-mcp", ranked.first().title)
    }

    @Test
    fun `exact title match is boosted`() {
        val merged = merger.merge(
            listOf(
                hit("github", "https://github.com/a/other", title = "Other thing", rank = 0),
                hit("github", "https://github.com/a/ollama", title = "Ollama", rank = 3),
            ),
            contexts("github"),
        )
        assertEquals("Ollama", ranking.rank(merged, "ollama", null).first().title)
    }

    @Test
    fun `path boosts matching audience without removing others`() {
        val base = merger.merge(
            listOf(
                hit("github", "https://github.com/a/dev", rank = 0),
                hit("github", "https://github.com/a/app", rank = 1),
            ),
            contexts("github"),
        ).map { if (it.title == "app") it.copy(audience = Audience.USE_AI) else it.copy(audience = Audience.BUILD_WITH_AI) }

        val useAi = ranking.rank(base, "x", AudiencePath.USE_AI)
        val build = ranking.rank(base, "x", AudiencePath.BUILD_WITH_AI)
        assertEquals("app", useAi.first().title)
        assertEquals("dev", build.first().title)
        assertEquals(2, useAi.size)
    }

    @Test
    fun `ranking is deterministic for ties`() {
        val merged = merger.merge(
            listOf(hit("a", "https://github.com/a/b1", rank = 0), hit("b", "https://github.com/a/b2", rank = 0)),
            contexts("a", "b"),
        )
        assertEquals(ranking.rank(merged, "q", null).map { it.id }, ranking.rank(merged.reversed(), "q", null).map { it.id })
    }
}
