package ai.helper2.search

import ai.helper2.shared.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompatibilityTest {

    private val merger = ResultMerger()

    private fun merged(vararg hits: SourceHit) = merger.merge(hits.toList(), contexts("github", "npm", "hf-models")).single()

    @Test
    fun `tags become flags naming the source that stated them`() {
        val flags = Compatibility.of(merged(hit("npm", "https://www.npmjs.com/package/x", tags = setOf("openai", "Anthropic", "langchain"))))
        assertEquals(listOf("openai", "anthropic", "langchain"), flags.map { it.tool })
        assertTrue(flags.all { it.basis == CompatibilityBasis.TAG && it.sourceId == "npm" })
        assertEquals("Anthropic", flags[1].evidence)
    }

    @Test
    fun `an MCP server works with MCP clients by protocol`() {
        val flag = Compatibility.of(merged(hit("github", "https://github.com/a/srv", type = ResourceType.MCP_SERVER))).single()
        assertEquals("mcp", flag.tool)
        assertEquals(CompatibilityBasis.TYPE, flag.basis)
        assertNull(flag.sourceId)
    }

    @Test
    fun `GGUF weights run on llama cpp and Ollama`() {
        val flags = Compatibility.of(merged(hit("hf-models", "https://huggingface.co/a/m-GGUF", type = ResourceType.MODEL, tags = setOf("gguf"))))
        assertEquals(listOf("ollama", "llama-cpp"), flags.map { it.tool })
        assertTrue(flags.all { it.basis == CompatibilityBasis.FORMAT })
    }

    @Test
    fun `tag spelling variants normalize and unrelated tags give nothing`() {
        val flags = Compatibility.of(merged(hit("github", "https://github.com/a/b", tags = setOf("Model_Context_Protocol", "llama", "gpt2", "rag"))))
        // "llama" is an architecture and "gpt2" a model name, not a provider API: no flag.
        assertEquals(listOf("mcp"), flags.map { it.tool })
    }

    @Test
    fun `the first source to state a tag is credited once`() {
        val flags = Compatibility.of(merged(
            hit("github", "https://github.com/a/c", tags = setOf("openai")),
            hit("npm", "https://www.npmjs.com/package/c", repo = "https://github.com/a/c", tags = setOf("openai")),
        ))
        assertEquals(1, flags.size)
    }

    @Test
    fun `the Nim language tag is not NVIDIA NIM`() {
        val flags = Compatibility.of(merged(hit("github", "https://github.com/a/nim-llm", tags = setOf("nim", "llm"))))
        assertEquals(emptyList(), flags.map { it.tool })
        assertEquals(listOf("nvidia"), Compatibility.of(merged(hit("github", "https://github.com/a/n", tags = setOf("nvidia-nim")))).map { it.tool })
    }
}
