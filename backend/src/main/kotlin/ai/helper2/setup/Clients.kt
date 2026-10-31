package ai.helper2.setup

import ai.helper2.setup.Formats.json
import ai.helper2.setup.Formats.sh
import ai.helper2.setup.Formats.toml
import ai.helper2.setup.Formats.tomlKey
import java.net.URI

/** One snippet for one run option in one client: ready to paste, or why it cannot be used there. */
data class Snippet(
    val optionId: String,
    val supported: Boolean,
    val where: String? = null,
    val language: String? = null,
    val text: String? = null,
    val note: String? = null,
)

/** What a client needs to render a run option, resolved once: values the user must supply are `<placeholders>`. */
data class Target(
    val key: String,
    val optionId: String,
    val option: RunOption,
    val launch: Launch?,
    /** Environment variables (stdio) or headers (remote) to set: the entry's value, or a placeholder. */
    val values: Map<String, String>,
) {
    val url: String? get() = (option as? RunOption.Remote)?.url?.toString()
    val stdio: Boolean get() = option !is RunOption.Remote
    val sse: Boolean get() = option.transport == Transport.SSE
}

/**
 * An AI client: where its MCP configuration goes and its format, as the client's own documentation states it
 * ([docsUrl], read 2026-10-27). [render] returns null for an option the documentation gives no way to configure,
 * and the snippet says so instead.
 */
class Client(
    val id: String,
    val name: String,
    val docsUrl: URI,
    private val render: (Target) -> Snippet?,
) {
    fun snippet(t: Target): Snippet = render(t) ?: Snippet(t.optionId, false, note = if (t.sse) NO_SSE else NO_WAY)

    private companion object {
        const val NO_SSE = "This client's documentation does not describe connecting to an SSE server."
        const val NO_WAY = "This client's documentation does not describe a way to configure this."
    }
}

/** The eight MCP clients with the widest reach, in display order. Adding a client is adding a row. */
object Clients {

    val ALL: List<Client> = listOf(
        Client("claude-code", "Claude Code", URI("https://code.claude.com/docs/en/mcp"), { t ->
            val words = if (t.stdio) {
                val l = t.launch!!
                listOf("claude", "mcp", "add") + t.values.flatMap { (k, v) -> listOf("--env", "$k=$v") } +
                    listOf("--transport", "stdio", t.key, "--", l.command) + l.args
            } else {
                listOf("claude", "mcp", "add", "--transport", if (t.sse) "sse" else "http", t.key, t.url!!) +
                    t.values.flatMap { (k, v) -> listOf("--header", "$k: $v") }
            }
            Snippet(t.optionId, true, "Terminal", "shell", words.joinToString(" ") { sh(it) },
                "Add --scope user to use it in every project, or --scope project to share it in .mcp.json.")
        }),
        Client("claude-desktop", "Claude Desktop",
            URI("https://modelcontextprotocol.io/docs/develop/connect-local-servers"), { t ->
                if (t.stdio) {
                    jsonSnippet(t, "claude_desktop_config.json (Settings › Developer › Edit Config)", "mcpServers", stdioEntry(t))
                } else {
                    Snippet(t.optionId, true, "Settings › Connectors › Add custom connector", null, t.url,
                        "Remote servers are added as a custom connector with this URL, not in the config file." +
                            if (t.values.isNotEmpty()) " Fixed headers can only be set by an Owner on Team or Enterprise plans." else "")
                }
            }),
        Client("cursor", "Cursor", URI("https://cursor.com/docs/mcp"), { t ->
            jsonSnippet(t, ".cursor/mcp.json (project) or ~/.cursor/mcp.json", "mcpServers",
                if (t.stdio) stdioEntry(t) else urlEntry(t))
        }),
        Client("vscode", "VS Code (GitHub Copilot)",
            URI("https://code.visualstudio.com/docs/agents/reference/mcp-configuration"), { t -> vsCode(t) }),
        Client("codex", "OpenAI Codex", URI("https://learn.chatgpt.com/docs/extend/mcp"), { t ->
            when {
                t.sse -> null
                else -> Snippet(t.optionId, true, "~/.codex/config.toml", "toml", codex(t))
            }
        }),
        Client("windsurf", "Windsurf (Devin Desktop)", URI("https://docs.devin.ai/desktop/cascade/mcp"), { t ->
            jsonSnippet(t, "~/.config/devin/mcp_config.json", "mcpServers",
                if (t.stdio) stdioEntry(t) else mapOf("serverUrl" to t.url) + headers(t))
                .copy(note = "This file is read by the Cascade agent; the Devin Local agent reads the Devin CLI configuration.")
        }),
        Client("gemini-cli", "Gemini CLI", URI("https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/mcp-server.md"), { t ->
            jsonSnippet(t, ".gemini/settings.json (project) or ~/.gemini/settings.json", "mcpServers", when {
                t.stdio -> stdioEntry(t)
                // Gemini CLI: httpUrl is Streamable HTTP, url is SSE.
                t.sse -> mapOf("url" to t.url) + headers(t)
                else -> mapOf("httpUrl" to t.url) + headers(t)
            })
        }),
        Client("cline", "Cline", URI("https://docs.cline.bot/mcp/mcp-overview"), { t ->
            jsonSnippet(t, "Cline › MCP Servers › Configure (cline_mcp_settings.json)", "mcpServers", when {
                t.stdio -> stdioEntry(t) + linkedMapOf("disabled" to false, "autoApprove" to emptyList<String>())
                // Without a type Cline assumes the legacy SSE transport.
                else -> mapOf("type" to if (t.sse) "sse" else "streamableHttp") + urlEntry(t)
            })
        }),
    )

    /** stdio entry in the common `command` / `args` / `env` shape. */
    internal fun stdioEntry(t: Target): Map<String, Any?> {
        val l = t.launch!!
        return linkedMapOf<String, Any?>("command" to l.command, "args" to l.args) +
            (if (t.values.isEmpty()) emptyMap() else mapOf("env" to t.values))
    }

    internal fun urlEntry(t: Target): Map<String, Any?> = mapOf("url" to t.url) + headers(t)

    private fun headers(t: Target): Map<String, Any?> = if (t.values.isEmpty()) emptyMap() else mapOf("headers" to t.values)

    private fun jsonSnippet(t: Target, where: String, root: String, entry: Map<String, Any?>) =
        Snippet(t.optionId, true, where, "json", json(mapOf(root to mapOf(t.key to entry))))

    /** VS Code asks for secrets with a password prompt (`inputs`) instead of keeping them in the file. */
    private fun vsCode(t: Target): Snippet {
        val secrets = t.option.settings.filter { it.secret && it.name in t.values }
        val values = t.values.mapValues { (k, v) -> if (secrets.any { it.name == k }) "\${input:${inputId(k)}}" else v }
        val entry: Map<String, Any?> = if (t.stdio) {
            linkedMapOf<String, Any?>("type" to "stdio", "command" to t.launch!!.command, "args" to t.launch.args) +
                (if (values.isEmpty()) emptyMap() else mapOf("env" to values))
        } else {
            linkedMapOf<String, Any?>("type" to if (t.sse) "sse" else "http", "url" to t.url) +
                (if (values.isEmpty()) emptyMap() else mapOf("headers" to values))
        }
        val doc = linkedMapOf<String, Any?>()
        if (secrets.isNotEmpty()) {
            doc["inputs"] = secrets.map {
                linkedMapOf("type" to "promptString", "id" to inputId(it.name), "description" to (it.description ?: it.name), "password" to true)
            }
        }
        doc["servers"] = mapOf(t.key to entry)
        return Snippet(t.optionId, true, ".vscode/mcp.json", "json", json(doc),
            if (secrets.isEmpty()) null else "VS Code asks for the secret values when the server starts.")
    }

    private fun inputId(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun codex(t: Target): String {
        val table = "mcp_servers." + tomlKey(t.key)
        val lines = mutableListOf("[$table]")
        if (t.stdio) {
            lines += "command = " + toml(t.launch!!.command)
            lines += "args = " + toml(t.launch.args)
            if (t.values.isNotEmpty()) {
                lines += ""
                lines += "[$table.env]"
                t.values.forEach { (k, v) -> lines += tomlKey(k) + " = " + toml(v) }
            }
        } else {
            lines += "url = " + toml(t.url)
            if (t.values.isNotEmpty()) lines += "http_headers = " + toml(t.values)
        }
        return lines.joinToString("\n")
    }
}
