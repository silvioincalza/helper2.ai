package ai.helper2.search

import ai.helper2.shared.ResourceType

enum class CompatibilityBasis {
  TAG,
  TYPE,
  FORMAT,
}

data class CompatibilityFlag(
  val tool: String,
  val label: String,
  val basis: CompatibilityBasis,
  val evidence: String,
  val sourceId: String?,
)

object Compatibility {

  data class Tool(
    val id: String,
    val label: String,
    val tags: Set<String>,
    val formats: Set<String> = emptySet(),
  )

  val TOOLS: List<Tool> =
    listOf(
      Tool(
        "openai",
        "OpenAI",
        setOf("openai", "openai-api", "chatgpt", "gpt-4", "gpt-4o", "gpt-5"),
      ),
      Tool(
        "anthropic",
        "Anthropic Claude",
        setOf("anthropic", "claude", "claude-api", "anthropic-claude"),
      ),
      Tool(
        "google",
        "Google Gemini",
        setOf("gemini", "gemini-api", "google-gemini", "google-genai"),
      ),
      Tool("xai", "xAI Grok", setOf("grok", "xai", "x-ai", "grok-api")),
      Tool("mistral", "Mistral", setOf("mistral", "mistralai", "mistral-ai")),
      Tool("deepseek", "DeepSeek", setOf("deepseek", "deepseek-api")),
      Tool("nvidia", "NVIDIA NIM", setOf("nvidia-nim", "nvidia-ai")),
      Tool("ollama", "Ollama", setOf("ollama"), formats = setOf("gguf")),
      Tool(
        "llama-cpp",
        "llama.cpp",
        setOf("llama-cpp", "llamacpp", "llama.cpp"),
        formats = setOf("gguf"),
      ),
      Tool("mlx", "Apple MLX", setOf("mlx")),
      Tool("transformers", "Transformers", setOf("transformers")),
      Tool("vllm", "vLLM", setOf("vllm")),
      Tool("langchain", "LangChain", setOf("langchain", "langchain-js", "langgraph")),
      Tool("llamaindex", "LlamaIndex", setOf("llamaindex", "llama-index")),
      Tool(
        "mcp",
        "Any MCP client",
        setOf("mcp", "mcp-server", "model-context-protocol", "modelcontextprotocol"),
      ),
      Tool(
        "claude-code",
        "Claude Code",
        setOf("claude-code", "claude-code-skill", "claude-skills"),
      ),
      Tool("cursor", "Cursor", setOf("cursor", "cursor-ai")),
      Tool("vscode", "VS Code", setOf("vscode", "vscode-extension", "visual-studio-code")),
    )

  private val byId = TOOLS.associateBy { it.id }

  internal fun normalize(tag: String): String =
    tag.trim().lowercase().replace('_', '-').replace(' ', '-')

  fun of(result: MergedResult): List<CompatibilityFlag> {
    val flags = LinkedHashMap<String, CompatibilityFlag>()
    if (result.type == ResourceType.MCP_SERVER) {
      val mcp = byId.getValue("mcp")
      flags[mcp.id] =
        CompatibilityFlag(
          mcp.id,
          mcp.label,
          CompatibilityBasis.TYPE,
          ResourceType.MCP_SERVER.name,
          null,
        )
    }
    for (hit in result.hits) {
      for (raw in hit.tags) {
        val tag = normalize(raw)
        for (tool in TOOLS) {
          if (tool.id in flags) continue
          val basis =
            when (tag) {
              in tool.tags -> CompatibilityBasis.TAG
              in tool.formats -> CompatibilityBasis.FORMAT
              else -> null
            } ?: continue
          flags[tool.id] = CompatibilityFlag(tool.id, tool.label, basis, raw, hit.sourceId)
        }
      }
    }
    return TOOLS.mapNotNull { flags[it.id] }
  }
}
