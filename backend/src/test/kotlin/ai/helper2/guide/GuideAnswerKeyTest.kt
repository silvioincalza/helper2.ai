package ai.helper2.guide

import ai.helper2.shared.AudiencePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Two spellings of one question share a cache key; two questions that can get different answers never do. */
class GuideAnswerKeyTest {

    private fun key(request: String, clarification: String? = null, drops: Drops = Drops.NONE) =
        GuideAnswerCache.key(request, null, "config", clarification, drops)

    @Test
    fun `case, Unicode width, spacing, trailing punctuation and quotes do not change the key`() {
        val same = listOf(
            "What is MCP?" to "what is mcp",
            "  Ollama   vs\tLM Studio \n" to "ollama vs lm studio",
            "what is ＭＣＰ" to "what is mcp", // full-width letters
            "free tool to chat with PDFs" to "free tool to chat with pdfs", // no-break space
            "chat​ with pdfs" to "chat with pdfs", // zero-width space
            "“Cursor” alternatives" to "cursor alternatives",
            "\"Cursor\" alternatives" to "cursor alternatives",
            "'Cursor' alternatives" to "cursor alternatives",
            "`ollama` vs «lm studio»" to "ollama vs lm studio",
            "what’s RAG" to "what's rag",
            "what is c++?!" to "what is c++",
            "is there a tool for node.js..." to "is there a tool for node.js",
            "MCP server for Postgres …" to "mcp server for postgres",
        )
        same.forEach { (a, b) -> assertEquals(key(b), key(a), "\"$a\" and \"$b\" must share a key") }
    }

    @Test
    fun `word order, inner punctuation, negation and wording stay in the key`() {
        val different = listOf(
            "ollama vs lm studio" to "lm studio vs ollama", // column order of the comparison
            "python bindings for rust" to "rust bindings for python",
            "what is c++" to "what is c",
            "a tool for node.js" to "a tool for nodejs",
            "what is .net" to "what is net",
            "open-source llm" to "open source llm",
            "gpt-4 alternatives" to "gpt 4 alternatives",
            "a free tool" to "a tool that is not free",
            "what's rag" to "whats rag",
            "llama 3" to "llama 3.1",
        )
        different.forEach { (a, b) -> assertNotEquals(key(a), key(b), "\"$a\" and \"$b\" must not share a key") }
    }

    @Test
    fun `the answer to a question back and the removed parts of the reading are part of the key`() {
        assertNotEquals(key("gemini"), key("gemini", clarification = "Gemini (model)"))
        assertNotEquals(key("gemini", clarification = "Gemini (model)"), key("gemini", clarification = "gemini cli (tool)"))
        assertNotEquals(key("gemini"), key("gemini", clarification = ""), "\"search anyway\" is an answer too")
        assertEquals(key("gemini", clarification = "Gemini (model)"), key("Gemini?", clarification = " gemini (MODEL) "))
        assertNotEquals(key("new mcp servers"), key("new mcp servers", drops = Drops(window = true)))
    }

    @Test
    fun `the audience path and the config are part of the key`() {
        assertNotEquals(GuideAnswerCache.key("rag", AudiencePath.BUILD_WITH_AI, "c"), GuideAnswerCache.key("rag", AudiencePath.USE_AI, "c"))
        assertNotEquals(GuideAnswerCache.key("rag", null, "c1"), GuideAnswerCache.key("rag", null, "c2"))
    }

    @Test
    fun `the key is a hash, so a store never sees the question`() {
        val key = key("speech to text I can self-host")

        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertFalse(key.contains("speech"))
    }

    @Test
    fun `normalising twice changes nothing`() {
        val once = GuideAnswerCache.normalized("  “What’s”  MCP?? ")
        assertEquals("what's mcp", once)
        assertEquals(once, GuideAnswerCache.normalized(once))
    }
}
