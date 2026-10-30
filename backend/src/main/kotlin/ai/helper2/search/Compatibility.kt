package ai.helper2.search

import ai.helper2.shared.ResourceType

/** Why a compatibility flag was shown. Every flag points back at something a source actually said. */
enum class CompatibilityBasis {
    /** A source listed this tag, topic or keyword on the resource. */
    TAG,
    /** The resource type implies it, by protocol (an MCP server works with MCP clients). */
    TYPE,
    /** The published file format implies it (GGUF weights run on llama.cpp and Ollama). */
    FORMAT,
}

/** "Works with X". [tool] is a stable id from [Compatibility.TOOLS]; [evidence] is the tag or type seen. */
data class CompatibilityFlag(
    val tool: String,
    val label: String,
    val basis: CompatibilityBasis,
    val evidence: String,
    /** Source that stated the tag; null for TYPE flags, which come from Helper2's own classification. */
    val sourceId: String?,
)

/**
 * Deterministic "compatible with" flags derived only from source data: tags/topics/keywords the
 * sources returned, the resource type and published formats. No model call, nothing guessed; the
 * rules are disclosed. Provider tool ids match the model-family ids of the catalog so a flag can
 * link to its family and the comparison page.
 */
object Compatibility {

    data class Tool(val id: String, val label: String, val tags: Set<String>, val formats: Set<String> = emptySet())

    val TOOLS: List<Tool> = listOf(
        // Model providers (APIs / SDKs).
        Tool("openai", "OpenAI", setOf("openai", "openai-api", "chatgpt", "gpt-4", "gpt-4o", "gpt-5")),
        Tool("anthropic", "Anthropic Claude", setOf("anthropic", "claude", "claude-api", "anthropic-claude")),
        Tool("google", "Google Gemini", setOf("gemini", "gemini-api", "google-gemini", "google-genai")),
        Tool("xai", "xAI Grok", setOf("grok", "xai", "x-ai", "grok-api")),
        Tool("mistral", "Mistral", setOf("mistral", "mistralai", "mistral-ai")),
        Tool("deepseek", "DeepSeek", setOf("deepseek", "deepseek-api")),
        Tool("nvidia", "NVIDIA NIM", setOf("nvidia-nim", "nvidia-ai")),
        // Local runtimes.
        Tool("ollama", "Ollama", setOf("ollama"), formats = setOf("gguf")),
        Tool("llama-cpp", "llama.cpp", setOf("llama-cpp", "llamacpp", "llama.cpp"), formats = setOf("gguf")),
        Tool("mlx", "Apple MLX", setOf("mlx")),
        Tool("transformers", "Transformers", setOf("transformers")),
        Tool("vllm", "vLLM", setOf("vllm")),
        // Frameworks.
        Tool("langchain", "LangChain", setOf("langchain", "langchain-js", "langgraph")),
        Tool("llamaindex", "LlamaIndex", setOf("llamaindex", "llama-index")),
        // Clients and editors.
        Tool("mcp", "Any MCP client", setOf("mcp", "mcp-server", "model-context-protocol", "modelcontextprotocol")),
        Tool("claude-code", "Claude Code", setOf("claude-code", "claude-code-skill", "claude-skills")),
        Tool("cursor", "Cursor", setOf("cursor", "cursor-ai")),
        Tool("vscode", "VS Code", setOf("vscode", "vscode-extension", "visual-studio-code")),
    )

    private val byId = TOOLS.associateBy { it.id }

    /** Tags arrive as "Model_Context_Protocol", "llama.cpp", "LangChain": compare a normalized form. */
    internal fun normalize(tag: String): String = tag.trim().lowercase().replace('_', '-').replace(' ', '-')

    fun of(result: MergedResult): List<CompatibilityFlag> {
        val flags = LinkedHashMap<String, CompatibilityFlag>()
        if (result.type == ResourceType.MCP_SERVER) {
            val mcp = byId.getValue("mcp")
            flags[mcp.id] = CompatibilityFlag(mcp.id, mcp.label, CompatibilityBasis.TYPE, ResourceType.MCP_SERVER.name, null)
        }
        // Per hit so each flag names the source that stated the tag; the first source wins.
        for (hit in result.hits) {
            for (raw in hit.tags) {
                val tag = normalize(raw)
                for (tool in TOOLS) {
                    if (tool.id in flags) continue
                    val basis = when (tag) {
                        in tool.tags -> CompatibilityBasis.TAG
                        in tool.formats -> CompatibilityBasis.FORMAT
                        else -> null
                    } ?: continue
                    flags[tool.id] = CompatibilityFlag(tool.id, tool.label, basis, raw, hit.sourceId)
                }
            }
        }
        // Stable display order: the order of TOOLS, not the order tags happened to arrive in.
        return TOOLS.mapNotNull { flags[it.id] }
    }
}
