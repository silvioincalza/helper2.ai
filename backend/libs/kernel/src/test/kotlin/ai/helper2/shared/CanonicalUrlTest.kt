package ai.helper2.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CanonicalUrlTest {

  private fun canon(raw: String) = CanonicalUrl.of(raw)?.value

  @Test
  fun `normalizes scheme host www trailing slash and fragment`() {
    assertEquals("https://example.com/tool", canon("HTTP://WWW.Example.com/tool/#pricing"))
  }

  @Test
  fun `drops tracking parameters and sorts the rest`() {
    assertEquals(
      "https://example.com/p?a=1&b=2",
      canon("https://example.com/p?utm_source=x&b=2&ref=hn&a=1&gclid=z"),
    )
  }

  @Test
  fun `a github repository is an owner and a repo, not a page of the site`() {
    assertEquals(
      true,
      CanonicalUrl.of("https://github.com/tensorflow/tensorflow/tags")?.isGitHubRepository,
    )
    assertEquals(false, CanonicalUrl.of("https://github.com/sponsors/acme")?.isGitHubRepository)
    assertEquals(
      false,
      CanonicalUrl.of("https://github.com/orgs/acme/repositories")?.isGitHubRepository,
    )
    assertEquals(false, CanonicalUrl.of("https://github.com/acme")?.isGitHubRepository)
    assertEquals(false, CanonicalUrl.of("https://gitlab.com/acme/tool")?.isGitHubRepository)
  }

  @Test
  fun `github urls collapse to lowercase owner and repo`() {
    assertEquals("https://github.com/owner/repo", canon("https://github.com/Owner/Repo.git"))
    assertEquals(
      "https://github.com/owner/repo",
      canon("https://github.com/Owner/Repo/blob/main/README.md"),
    )
    assertEquals("https://github.com/owner/repo", canon("https://github.com/Owner/Repo/tree/main/"))
    assertEquals("https://github.com/owner/repo", canon("git://github.com/owner/repo.git"))
  }

  @Test
  fun `a github sub-directory keeps its path, so each monorepo package has its own identity`() {
    assertEquals(
      "https://github.com/owner/repo/tree/HEAD/packages/Player",
      canon("git+https://github.com/Owner/Repo/tree/main/packages/Player/"),
    )
    assertNotEquals(
      canon("https://github.com/owner/repo/tree/main/packages/a"),
      canon("https://github.com/owner/repo/tree/main/packages/b"),
    )
    assertNotEquals(
      canon("https://github.com/owner/repo"),
      canon("https://github.com/owner/repo/tree/main/packages/a"),
    )
  }

  @Test
  fun `keeps case of non github paths`() {
    assertEquals(
      "https://huggingface.co/Qwen/Qwen3-8B",
      canon("https://huggingface.co/Qwen/Qwen3-8B/"),
    )
  }

  @Test
  fun `drops default port and keeps root slash free`() {
    assertEquals("https://example.com", canon("https://example.com:443/"))
    assertEquals("https://example.com:8443/x", canon("https://example.com:8443/x"))
  }

  @Test
  fun `rejects non web schemes and garbage`() {
    assertNull(canon("javascript:alert(1)"))
    assertNull(canon("ftp://example.com/file"))
    assertNull(canon("not a url"))
    assertNull(canon(""))
    assertNull(canon("https://"))
  }

  @Test
  fun `different resources stay different`() {
    assertNotEquals(canon("https://github.com/a/b"), canon("https://github.com/a/c"))
  }

  @Test
  fun `github site pages keep their path, so each page is its own resource`() {
    assertEquals(
      "https://github.com/marketplace/actions/setup-node",
      canon("https://github.com/marketplace/actions/setup-node/"),
    )
    assertNotEquals(
      canon("https://github.com/marketplace/models/azure-openai/gpt-4o"),
      canon("https://github.com/marketplace/models/meta/llama-3"),
    )
    assertEquals(
      "https://github.com/features/copilot",
      canon("https://github.com/features/copilot"),
    )
    assertEquals("https://github.com/topics/llm", canon("https://github.com/topics/llm"))
  }

  @Test
  fun `a github sub-directory is one resource under any branch or tag`() {
    val folder = "https://github.com/owner/repo/tree/HEAD/packages/a"

    assertEquals(folder, canon("https://github.com/owner/repo/tree/HEAD/packages/a"))
    assertEquals(folder, canon("https://github.com/owner/repo/tree/main/packages/a"))
    assertEquals(folder, canon("https://github.com/owner/repo/tree/v2.1.0/packages/a"))
    assertEquals(folder, canon("https://github.com/owner/repo/blob/master/packages/a/README.md"))
  }

  @Test
  fun `a github file names its repository, but a folder readme names its folder`() {
    assertEquals(
      "https://github.com/owner/prompts",
      canon("https://github.com/Owner/Prompts/blob/main/coding/Review.md"),
    )
    assertEquals(
      "https://github.com/owner/repo",
      canon("https://github.com/owner/repo/blob/main/LICENSE"),
    )
    assertEquals(
      "https://github.com/owner/repo",
      canon("https://github.com/owner/repo/blob/main/README.md"),
    )
    assertEquals(
      "https://github.com/owner/repo/tree/HEAD/pkg/x",
      canon("https://github.com/owner/repo/blob/main/pkg/x/README.md"),
    )
    assertEquals(
      canon("https://github.com/owner/repo/tree/main/pkg/x"),
      canon("https://github.com/owner/repo/blob/main/pkg/x/readme.rst"),
    )
  }

  @Test
  fun `other github repository pages name the repository`() {
    assertEquals("https://github.com/owner/repo", canon("https://github.com/owner/repo/issues/12"))
    assertEquals("https://github.com/owner/repo", canon("https://github.com/owner/repo/releases"))
  }

  @Test
  fun `every view and version of an arxiv paper is one resource`() {
    val abs = "https://arxiv.org/abs/2401.12345"
    assertEquals(abs, canon("http://arxiv.org/abs/2401.12345v2"))
    assertEquals(abs, canon("https://arxiv.org/pdf/2401.12345v3.pdf"))
    assertEquals(abs, canon("https://arxiv.org/pdf/2401.12345"))
    assertEquals(abs, canon("https://arxiv.org/html/2401.12345v1/"))
    assertEquals(abs, canon("https://export.arxiv.org/abs/2401.12345"))
    assertEquals(
      "https://arxiv.org/abs/hep-th/9901001",
      canon("https://arxiv.org/abs/hep-th/9901001v1"),
    )
    assertEquals(
      "https://arxiv.org/list/cs.AI/recent",
      canon("https://arxiv.org/list/cs.AI/recent"),
    )
  }

  @Test
  fun `a hugging face paper page is the arxiv paper`() {
    assertEquals(
      "https://arxiv.org/abs/2401.12345",
      canon("https://huggingface.co/papers/2401.12345"),
    )
  }

  @Test
  fun `hugging face repository pages name the repository`() {
    assertEquals("https://huggingface.co/Qwen/Qwen3-8B", canon("https://hf.co/Qwen/Qwen3-8B"))
    assertEquals(
      "https://huggingface.co/Qwen/Qwen3-8B",
      canon("https://huggingface.co/Qwen/Qwen3-8B/blob/main/README.md"),
    )
    assertEquals(
      "https://huggingface.co/datasets/openai/gsm8k",
      canon("https://huggingface.co/datasets/openai/gsm8k/tree/main"),
    )
    assertEquals(
      "https://huggingface.co/spaces/owner/demo",
      canon("https://huggingface.co/spaces/owner/demo?logs=container"),
    )
    assertEquals(
      "https://huggingface.co/models?sort=trending",
      canon("https://huggingface.co/models?sort=trending"),
    )
  }

  @Test
  fun `a path with dot segments is refused, so it cannot pose as another resource`() {
    assertNull(canon("https://github.com/victim/mcp/tree/HEAD/src/../../../../attacker/evil"))
    assertNull(canon("https://github.com/victim/mcp/tree/HEAD/src/%2e%2e/%2E%2E/x"))
    assertNull(canon("https://example.com/a/./b"))
  }

  @Test
  fun `hugging face site pages keep their path`() {
    assertNotEquals(
      canon("https://huggingface.co/collections/owner/one"),
      canon("https://huggingface.co/collections/owner/two"),
    )
    assertEquals(
      "https://huggingface.co/chat/assistant/abc",
      canon("https://huggingface.co/chat/assistant/abc"),
    )
  }
}
