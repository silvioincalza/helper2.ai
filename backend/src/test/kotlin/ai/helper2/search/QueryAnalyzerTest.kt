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
        assertEquals(listOf("stream llm responses in react js", "stream llm responses in react javascript"), q.requiredVariants)
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
}
