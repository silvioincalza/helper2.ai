package ai.helper2.catalog

import ai.helper2.TestcontainersConfiguration
import ai.helper2.catalog.internal.ResourceStore
import ai.helper2.search.NOW
import ai.helper2.search.PopularityKind
import ai.helper2.search.PopularityMetric
import ai.helper2.search.QueryAnalyzer
import ai.helper2.search.ResultMerger
import ai.helper2.search.ServedFrom
import ai.helper2.search.SourceContext
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.catalog.internal.ResourceStore.DataOrigin
import ai.helper2.shared.ResourceType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The prod case of 2026-10-26, reduced to three resources: why the well-known server was invisible. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CatalogSearchRankingIT {

    @Autowired lateinit var catalog: ResourceCatalog
    @Autowired lateinit var store: ResourceStore
    @Autowired lateinit var jdbc: JdbcClient

    private val origins = setOf(DataOrigin.INDEXED, DataOrigin.OBSERVED)

    private fun stars(n: Long) = listOf(PopularityMetric(PopularityKind.GITHUB_STARS, n, "github", NOW))

    private fun search(text: String): List<String> {
        val q = QueryAnalyzer().analyze(text)
        return store.search(text, q.requiredVariants, q.expandedTerms, q.synonyms, null, origins, 50).map { it.title() }
    }

    @Test
    fun `a monitor that matches only through SQL Server no longer comes first`() {
        val hits = listOf(
            hit("github", "https://github.com/rank-it/sqlserver-monitor", title = "rank-it/sqlserver-monitor",
                type = ResourceType.MCP_SERVER,
                description = "Free SQL Server and Postgres performance monitor with an MCP endpoint").copy(popularity = stars(523)),
            hit("github", "https://github.com/rank-it/postgres-mcp-pro", title = "rank-it/postgres-mcp-pro",
                type = ResourceType.MCP_SERVER,
                description = "Postgres MCP Pro: configurable read/write access and performance analysis").copy(popularity = stars(3_424)),
            hit("github", "https://github.com/rank-it/pg-mcp", title = "rank-it/pg-mcp",
                type = ResourceType.MCP_SERVER,
                description = "PostgreSQL MCP server for AI assistants").copy(popularity = stars(1_715)),
        )
        catalog.index(ResultMerger().merge(hits, mapOf("github" to SourceContext(descriptor("github"), ServedFrom.LIVE))), "github")

        val titles = search("MCP server Postgres").filter { it.startsWith("rank-it/") }

        assertTrue(titles.containsAll(listOf("rank-it/postgres-mcp-pro", "rank-it/pg-mcp", "rank-it/sqlserver-monitor")), "$titles")
        assertNotEquals("rank-it/sqlserver-monitor", titles.first(), "$titles")
        assertTrue(titles.indexOf("rank-it/postgres-mcp-pro") < titles.indexOf("rank-it/sqlserver-monitor"), "$titles")
    }

    @Test
    fun `query text with quotes and operators is only ever a parameter`() {
        search("postgres'); DROP TABLE resource; -- OR \"x\" ||")
        val left = jdbc.sql("SELECT count(*) FROM resource").query(Long::class.java).single()
        assertTrue(left > 0)
    }
}
