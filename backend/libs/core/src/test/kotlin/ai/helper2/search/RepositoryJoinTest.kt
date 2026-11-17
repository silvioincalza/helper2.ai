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
  fun `a clone address is read without its git suffix`() {
    assertJoins(pypi("transformers", "https://gitlab.com/group/subgroup/transformers.git"))
  }

  @Test
  fun `a GitLab group named tree is a repository, its folder is after the dash`() {
    val nested = "https://gitlab.com/group/subgroup/tree/tools/transformers"
    assertNull(RepositoryJoin.of(npm("@someone/agents-kit", nested)))
    assertJoins(npm("@someone/agents-kit", "https://gitlab.com/group/tools/-/tree/main/agents-kit"))
  }

  @Test
  fun `an owner or a repository named tree is not taken for a folder marker`() {
    assertJoins(pypi("tree-client", "https://github.com/acme/tree/tree/HEAD/python"))
    assertJoins(pypi("tree-client", "https://github.com/tree/tree-client"))
  }

  @Test
  fun `a package joins when its name holds the repository's telling words`() {
    assertJoins(npm("firecrawl-mcp", "https://github.com/firecrawl/firecrawl-mcp-server"))
    assertJoins(pypi("postgres-mcp", "https://github.com/crystaldba/postgres-mcp-server"))
    assertNull(
      RepositoryJoin.of(npm("other-mcp", "https://github.com/firecrawl/firecrawl-mcp-server"))
    )
    assertNull(
      RepositoryJoin.of(npm("mcp-server", "https://github.com/someone/weather-mcp-server"))
    )
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
  fun `an unscoped package whose name does not hold the repository's name does not join`() {
    assertNull(RepositoryJoin.of(pypi("openai", "https://github.com/openai/openai-python")))
    assertNull(RepositoryJoin.of(pypi("torch", "https://github.com/pytorch/pytorch")))
    assertNull(RepositoryJoin.of(pypi("mcp", "https://github.com/modelcontextprotocol/python-sdk")))
  }

  @Test
  fun `a fork or a stranger whose name holds the repository's name still joins`() {
    assertJoins(pypi("divyanx-transformers", "https://github.com/huggingface/transformers"))
    assertJoins(pypi("vllm-npu", "https://github.com/vllm-project/vllm"))
    assertJoins(npm("ai-toolkit", "https://github.com/vercel/ai"))
  }

  @Test
  fun `a repository in a nested group is checked by its own name, not taken as a sub-directory`() {
    val nested = "https://gitlab.com/group/subgroup/transformers"
    assertNull(RepositoryJoin.of(npm("@someone/agents-kit", nested)))
    assertJoins(npm("@group/transformers-js", nested))
    assertJoins(pypi("transformers-lite", nested))
  }

  @Test
  fun `a repository stated by its host alone is named by the host`() {
    assertJoins(pypi("acme-sdk", "https://acme.dev"))
    assertNull(RepositoryJoin.of(pypi("other-sdk", "https://acme.dev")))
  }

  @Test
  fun `a repository named tree is a repository, and only a tree after the repository is a folder`() {
    assertNull(RepositoryJoin.of(npm("@someone/agents-kit", "https://github.com/acme/tree")))
    assertNull(RepositoryJoin.of(npm("@someone/agents-kit", "https://github.com/tree/tools")))
    val gitlabFolder = "https://gitlab.com/group/sub/repo/-/tree/main/packages/kit"
    assertEquals(gitlabFolder, RepositoryJoin.of(npm("@someone/kit", gitlabFolder))?.value)
  }

  @Test
  fun `a scoped package does not join a repository stated by its host alone by scope`() {
    assertNull(RepositoryJoin.of(npm("@someone/agents-kit", "https://acme.dev")))
    assertJoins(npm("@someone/acme-kit", "https://acme.dev"))
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
  fun `a registry server whose telling words are all in its repository's name joins it`() {
    assertJoins(server("ai.a1lane/a1lane", "https://github.com/a1lane/a1lane-mcp"))
    assertJoins(server("agency.goji/goji", "https://github.com/goji-agency/goji-mcp"))
    assertJoins(server("ai.1325/mcp", "https://github.com/1325ai/mcp-server"))
    assertJoins(
      server(
        "io.github.pipeworx-io/epa-emissions",
        "https://github.com/pipeworx-io/mcp-epa-emissions",
      )
    )
    assertJoins(server("io.github.open-leash/leash", "https://github.com/open-leash/leash-plugin"))
  }

  @Test
  fun `a registry server whose name holds every telling word of its repository's name joins it`() {
    assertJoins(
      server(
        "io.github.ansvar-systems/uk-livestock-mcp",
        "https://github.com/ansvar-systems/uk-livestock",
      )
    )
    assertJoins(
      server("io.github.stakemate369/8004swap-mcp", "https://github.com/stakemate369/8004swap")
    )
  }

  @Test
  fun `a registry server that shares only some telling words with its repository's name stays apart`() {
    assertNull(
      RepositoryJoin.of(
        server("io.github.acme/airtable-sync", "https://github.com/acme/airtable-tools")
      )
    )
  }

  @Test
  fun `a registry server with no word in common with its repository's name stays apart`() {
    assertNull(
      RepositoryJoin.of(
        server("com.wavebrook/dashboard", "https://github.com/wavebrook/ai-plugins")
      )
    )
  }

  @Test
  fun `the servers of collection repositories with other names stay apart`() {
    assertNull(
      RepositoryJoin.of(
        server("io.github.mrfentmen/airtable-mcp", "https://github.com/mrfentmen/premium-mcps")
      )
    )
    assertNull(
      RepositoryJoin.of(
        server("io.github.sadri-dridi/aba-ok", "https://github.com/sadri-dridi/named-mcp-utilities")
      )
    )
  }

  @Test
  fun `the servers of a repository with only generic words in its name join it only by sharing one`() {
    val repo = "https://github.com/modelcontextprotocol/servers"
    assertNull(RepositoryJoin.of(server("io.github.modelcontextprotocol/memory", repo)))
    assertNull(RepositoryJoin.of(server("io.github.modelcontextprotocol/filesystem", repo)))
    assertJoins(server("io.github.acme/mcp-server", "https://github.com/acme/mcp-server"))
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
