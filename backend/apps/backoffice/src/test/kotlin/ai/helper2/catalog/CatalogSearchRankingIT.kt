package ai.helper2.catalog

import ai.helper2.TestcontainersConfiguration
import ai.helper2.catalog.internal.JdbcCatalogSearch
import ai.helper2.catalog.usecase.IndexResources
import ai.helper2.search.NOW
import ai.helper2.search.PopularityKind
import ai.helper2.search.PopularityMetric
import ai.helper2.search.QueryAnalyzer
import ai.helper2.search.ResultMerger
import ai.helper2.search.ServedFrom
import ai.helper2.search.SourceContext
import ai.helper2.search.SourceHit
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.shared.ResourceType
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CatalogSearchRankingIT {

  @Autowired lateinit var indexResources: IndexResources
  @Autowired lateinit var catalogSearch: JdbcCatalogSearch
  @Autowired lateinit var jdbc: JdbcClient

  private val origins = setOf(DataOrigin.INDEXED, DataOrigin.OBSERVED)

  private fun stars(n: Long) =
    listOf(PopularityMetric(PopularityKind.GITHUB_STARS, n, "github", NOW))

  private fun npm(n: Long) =
    listOf(PopularityMetric(PopularityKind.NPM_DOWNLOADS_WEEKLY, n, "npm", NOW))

  private fun index(source: String, vararg hits: SourceHit) {
    indexResources.index(
      ResultMerger()
        .merge(hits.toList(), mapOf(source to SourceContext(descriptor(source), ServedFrom.LIVE))),
      source,
    )
  }

  private fun repo(name: String, starCount: Long, description: String) =
    hit("github", "https://github.com/$name", title = name, description = description)
      .copy(popularity = stars(starCount))

  private fun pkg(name: String, downloads: Long, description: String) =
    hit("npm", "https://www.npmjs.com/package/$name", title = name, description = description)
      .copy(popularity = npm(downloads))

  private fun ranked(text: String, among: Collection<String>): List<String> =
    search(text).filter { it in among }.also { println("ranking \"$text\": $it") }

  private fun search(text: String): List<String> {
    val q = QueryAnalyzer().analyze(text)
    return catalogSearch
      .search(text, q.requiredVariants, q.expandedTerms, q.synonyms, null, origins, 50, null)
      .map { it.title() }
  }

  @Test
  fun `a monitor that matches only through SQL Server no longer comes first`() {
    val hits =
      listOf(
        hit(
            "github",
            "https://github.com/rank-it/sqlserver-monitor",
            title = "rank-it/sqlserver-monitor",
            type = ResourceType.MCP_SERVER,
            description = "Free SQL Server and Postgres performance monitor with an MCP endpoint",
          )
          .copy(popularity = stars(523)),
        hit(
            "github",
            "https://github.com/rank-it/postgres-mcp-pro",
            title = "rank-it/postgres-mcp-pro",
            type = ResourceType.MCP_SERVER,
            description =
              "Postgres MCP Pro: configurable read/write access and performance analysis",
          )
          .copy(popularity = stars(3_424)),
        hit(
            "github",
            "https://github.com/rank-it/pg-mcp",
            title = "rank-it/pg-mcp",
            type = ResourceType.MCP_SERVER,
            description = "PostgreSQL MCP server for AI assistants",
          )
          .copy(popularity = stars(1_715)),
      )
    indexResources.index(
      ResultMerger()
        .merge(hits, mapOf("github" to SourceContext(descriptor("github"), ServedFrom.LIVE))),
      "github",
    )

    val titles = search("MCP server Postgres").filter { it.startsWith("rank-it/") }

    assertTrue(
      titles.containsAll(
        listOf("rank-it/postgres-mcp-pro", "rank-it/pg-mcp", "rank-it/sqlserver-monitor")
      ),
      "$titles",
    )
    assertNotEquals("rank-it/sqlserver-monitor", titles.first(), "$titles")
    assertTrue(
      titles.indexOf("rank-it/postgres-mcp-pro") < titles.indexOf("rank-it/sqlserver-monitor"),
      "$titles",
    )
  }

  @Test
  fun `ollama puts ollama-ollama first`() {
    val rows =
      listOf(
        repo("ollama/ollama", 160_000, "Get up and running with large language models"),
        repo("ollama/ollama-js", 4_000, "Ollama JavaScript library"),
        repo(
          "open-webui/open-webui",
          100_000,
          "User-friendly AI interface (supports Ollama, OpenAI API and more)",
        ),
      )
    index("github", *rows.toTypedArray())
    index("npm", pkg("ollama-ai-provider-v2", 50_000, "Vercel AI provider for Ollama"))

    val titles = ranked("ollama", rows.map { it.title } + "ollama-ai-provider-v2")

    assertEquals("ollama/ollama", titles.firstOrNull(), "$titles")
  }

  @Test
  fun `llama-cpp puts ggml-org-llama-cpp first, with or without the dot`() {
    index(
      "github",
      repo("ggml-org/llama.cpp", 90_000, "LLM inference in C/C++"),
      repo("abetlen/llama-cpp-python", 9_000, "Python bindings for llama.cpp"),
    )
    index(
      "npm",
      pkg(
        "node-llama-cpp",
        30_000,
        "Run AI models locally on your machine with node.js bindings for llama.cpp",
      ),
      pkg("llama-cpp-capacitor", 500, "A native Capacitor plugin for llama.cpp"),
    )
    val among =
      listOf(
        "ggml-org/llama.cpp",
        "abetlen/llama-cpp-python",
        "node-llama-cpp",
        "llama-cpp-capacitor",
      )

    val dotted = ranked("llama.cpp", among)
    val spaced = ranked("llama cpp", among)

    assertEquals("ggml-org/llama.cpp", dotted.firstOrNull(), "$dotted")
    assertTrue("ggml-org/llama.cpp" in spaced, "$spaced")
  }

  @Test
  fun `scoped npm names still match, whole or by their parts`() {
    index(
      "npm",
      pkg(
        "@modelcontextprotocol/sdk",
        9_000_000,
        "Model Context Protocol implementation for TypeScript",
      ),
      pkg(
        "@modelcontextprotocol/server-everything",
        20_000,
        "MCP server that exercises all the features of the MCP protocol",
      ),
      pkg("@modelcontextprotocol/inspector", 200_000, "Model Context Protocol inspector"),
    )
    val among =
      listOf(
        "@modelcontextprotocol/sdk",
        "@modelcontextprotocol/server-everything",
        "@modelcontextprotocol/inspector",
      )

    val whole = ranked("@modelcontextprotocol/sdk", among)
    val parts = ranked("modelcontextprotocol sdk", among)
    val inspector = ranked("modelcontextprotocol inspector", among)

    assertEquals("@modelcontextprotocol/sdk", whole.firstOrNull(), "$whole")
    assertEquals("@modelcontextprotocol/sdk", parts.firstOrNull(), "$parts")
    assertEquals("@modelcontextprotocol/inspector", inspector.firstOrNull(), "$inspector")
  }

  @Test
  fun `both postgres MCP servers are found`() {
    index(
      "github",
      repo(
        "crystaldba/postgres-mcp",
        1_500,
        "Postgres MCP Pro: tuning, health checks and safe SQL",
      ),
    )
    index(
      "npm",
      pkg("@microsoft/postgres-mcp", 3_000, "MCP server for Azure Database for PostgreSQL"),
    )

    val titles =
      ranked("mcp server postgres", listOf("crystaldba/postgres-mcp", "@microsoft/postgres-mcp"))

    assertEquals(2, titles.size, "$titles")
  }

  @Test
  fun `query text with quotes and operators is only ever a parameter`() {
    search("postgres'); DROP TABLE resource; -- OR \"x\" ||")
    val left = jdbc.sql("SELECT count(*) FROM resource").query(Long::class.java).single()
    assertTrue(left > 0)
  }
}
