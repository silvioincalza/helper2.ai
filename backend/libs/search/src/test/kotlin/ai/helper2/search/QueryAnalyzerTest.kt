package ai.helper2.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryAnalyzerTest {

  private val analyzer = QueryAnalyzer()

  @Test
  fun `italian intent words expand to english equivalents and use cases`() {
    val q = analyzer.analyze("strumento per tradurre e riassumere documenti")
    assertTrue("translation" in q.expandedTerms)
    assertTrue("summarization" in q.useCases)
    assertTrue("translation" in q.useCases)
  }

  @Test
  fun `english queries are not expanded`() {
    val q = analyzer.analyze("mcp server for postgres")
    assertEquals(emptyList(), q.expandedTerms)
  }

  @Test
  fun `mcp server is a type hint, not a required word, in either word order`() {
    val en = analyzer.analyze("MCP server Postgres")
    assertEquals(setOf(ai.helper2.shared.ResourceType.MCP_SERVER), en.typeHints)
    assertEquals(listOf("mcp postgres", "mcp postgresql"), en.requiredVariants)
    val it = analyzer.analyze("un server MCP per Google Drive")
    assertEquals(setOf(ai.helper2.shared.ResourceType.MCP_SERVER), it.typeHints)
    assertTrue(it.requiredVariants.none { "server" in it.split(' ') })
  }

  @Test
  fun `server without mcp stays a required word`() {
    val q = analyzer.analyze("sql server backup tool")
    assertEquals(emptySet(), q.typeHints)
    assertEquals(listOf("sql server backup tool"), q.requiredVariants)
  }

  @Test
  fun `synonyms become alternative variants and optional words, never extra required words`() {
    val q = analyzer.analyze("stream llm responses in react js")
    assertEquals(listOf("javascript"), q.synonyms)
    assertEquals(
      listOf("stream llm responses in react js", "stream llm responses in react javascript"),
      q.requiredVariants,
    )
  }

  @Test
  fun `variants are capped and a query made only of type words keeps the whole query`() {
    assertTrue(analyzer.analyze("postgres js ts k8s").requiredVariants.size <= 4)
    assertEquals(listOf("library"), analyzer.analyze("library").requiredVariants)
  }

  @Test
  fun `normalizes whitespace and case for cache keys`() {
    assertEquals("mcp postgres", analyzer.analyze("  MCP   Postgres ").normalized)
  }

  @Test
  fun `use case detection works for english intents`() {
    assertTrue("image-generation" in analyzer.analyze("generate images from text").useCases)
  }

  @Test
  fun `content terms drop function words, request words and type words`() {
    assertEquals(
      listOf("python", "retrieval-augmented", "generation", "pdf"),
      analyzer
        .analyze("Python library to build retrieval-augmented generation over PDF files")
        .contentTerms,
    )
    assertEquals(
      listOf("image", "segmentation"),
      analyzer.analyze("open model for image segmentation").contentTerms,
    )
    assertEquals(
      listOf("mixture", "experts"),
      analyzer.analyze("mixture of experts paper").contentTerms,
    )
    assertEquals(
      listOf("python", "chatbot"),
      analyzer.analyze("python library to create chatbot").contentTerms,
    )
  }

  @Test
  fun `content terms keep mcp and drop server next to it`() {
    assertEquals(
      listOf("mcp", "slack", "assistant", "read", "channels"),
      analyzer.analyze("MCP server for Slack so an assistant can read channels").contentTerms,
    )
  }

  @Test
  fun `a type word inside a name stays a content term`() {
    assertEquals(
      listOf("model", "context", "protocol", "announcement"),
      analyzer.analyze("model context protocol announcement").contentTerms,
    )
  }

  @Test
  fun `content terms keep a model id whole`() {
    assertEquals(
      listOf("sentence-transformers/all-minilm-l6-v2"),
      analyzer.analyze("sentence-transformers/all-MiniLM-L6-v2").contentTerms,
    )
    assertEquals(listOf("llama", "3.1", "gguf"), analyzer.analyze("Llama 3.1, GGUF?").contentTerms)
  }

  @Test
  fun `a query of filler words only keeps its words`() {
    assertEquals(listOf("ollama"), analyzer.analyze("ollama").contentTerms)
    assertEquals(listOf("best", "new", "tools"), analyzer.analyze("best new tools").contentTerms)
  }

  @Test
  fun `paper is a type hint`() {
    assertEquals(
      setOf(ai.helper2.shared.ResourceType.PAPER),
      analyzer.analyze("mixture of experts paper").typeHints,
    )
  }
}
