package ai.helper2.search

import ai.helper2.shared.CollectorIds
import ai.helper2.shared.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryJoinTest {

  @Test
  fun `a package whose scope is the repository's owner joins it, also by word`() {
    assertJoins(npm("@langchain/core", "https://github.com/langchain-ai/langchainjs"))
    assertJoins(npm("@tanstack/ai-openai", "https://github.com/TanStack/ai"))
    assertJoins(
      npm("@microsoft/postgres-mcp-darwin-arm64", "https://github.com/microsoft/postgres-mcp")
    )
    assertJoins(
      npm("@modelcontextprotocol/server-memory", "https://github.com/modelcontextprotocol/servers")
    )
  }

  @Test
  fun `a package whose name holds the repository's name joins it`() {
    assertJoins(npm("@ai-sdk/openai", "https://github.com/vercel/ai"))
    assertJoins(pypi("langchain-openai", "https://github.com/langchain-ai/langchain"))
    assertJoins(npm("acme-mcp", "https://github.com/acme/acme-mcp"))
  }

  @Test
  fun `a package of another owner whose name does not hold the repository's name does not join`() {
    assertNull(RepositoryJoin.of(pypi("alpha-go", "https://github.com/lazy-punk/ml-utils")))
    assertNull(
      RepositoryJoin.of(npm("@someone/agents-kit", "https://github.com/huggingface/transformers"))
    )
    assertNull(RepositoryJoin.of(pypi("aeon-agents-sdk", "https://github.com/livekit/python-sdks")))
  }

  @Test
  fun `a fork whose name holds the repository's name still joins`() {
    assertJoins(pypi("divyanx-transformers", "https://github.com/huggingface/transformers"))
    assertJoins(pypi("vllm-npu", "https://github.com/vllm-project/vllm"))
  }

  @Test
  fun `a stated sub-directory is joined as stated`() {
    val folder = "https://github.com/remotion-dev/remotion/tree/HEAD/packages/player"
    assertEquals(folder, RepositoryJoin.of(npm("@remotion/player", folder))?.value)
  }

  @Test
  fun `a registry server whose name holds the repository's name joins it`() {
    assertJoins(
      server("io.github.github/github-mcp-server", "https://github.com/github/github-mcp-server")
    )
  }

  @Test
  fun `the servers of a collection repository are not joined by their owner alone`() {
    val repo = "https://github.com/codespar/mcp-dev-latam"
    assertNull(RepositoryJoin.of(server("io.github.codespar/mcp-adyen", repo)))
    assertNull(RepositoryJoin.of(server("io.github.codespar/mcp-afip", repo)))
  }

  @Test
  fun `a registry server in a sub-folder never joins the repository root`() {
    val hit =
      server("io.github.awslabs/a", "https://github.com/awslabs/mcp")
        .copy(canonicalUrl = canonical("https://github.com/awslabs/mcp/tree/HEAD/src/a-server"))
    assertNull(RepositoryJoin.of(hit))
  }

  @Test
  fun `a hit at its repository's own address joins nothing`() {
    assertNull(
      RepositoryJoin.of(hit("github", "https://github.com/a/b", repo = "https://github.com/a/b"))
    )
  }

  private fun assertJoins(hit: SourceHit) = assertEquals(hit.repositoryUrl, RepositoryJoin.of(hit))

  private fun npm(name: String, repo: String) =
    hit("npm", "https://www.npmjs.com/package/$name", repo = repo)

  private fun pypi(name: String, repo: String) =
    hit(CollectorIds.PYPI, "https://pypi.org/project/$name/", repo = repo)

  private fun server(name: String, repo: String) =
    hit(
      CollectorIds.MCP_REGISTRY,
      "https://registry.modelcontextprotocol.io/v0.1/servers/${name.replace("/", "%2F")}",
      repo = repo,
      type = ResourceType.MCP_SERVER,
      name = name,
    )

  private fun canonical(url: String) = ai.helper2.shared.CanonicalUrl.of(url)!!
}
