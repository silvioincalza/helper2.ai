package ai.helper2.setup

import ai.helper2.setup.Formats.json
import ai.helper2.setup.Formats.sh
import ai.helper2.setup.Formats.toml
import ai.helper2.setup.Formats.tomlKey
import ai.helper2.setup.Formats.yaml
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
 * with the reason in [unsupported].
 */
class Client(
    val id: String,
    val name: String,
    val docsUrl: URI,
    private val render: (Target) -> Snippet?,
    private val unsupported: (Target) -> String = { if (it.sse) NO_SSE else NO_WAY },
) {
    fun snippet(t: Target): Snippet = render(t) ?: Snippet(t.optionId, false, note = unsupported(t))

    companion object {
        const val NO_SSE = "This client's documentation does not describe connecting to an SSE server."
        const val NO_WAY = "This client's documentation does not describe a way to configure this."
    }
}

/** The clients, in display order. Adding a client is adding a row. */
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
        Client("copilot-cli", "GitHub Copilot CLI",
            URI("https://code.visualstudio.com/docs/agents/reference/mcp-configuration"), { t ->
                when {
                    t.stdio -> jsonSnippet(t, "~/.copilot/mcp-config.json", "mcpServers", stdioEntry(t))
                    t.sse -> null
                    else -> jsonSnippet(t, "~/.copilot/mcp-config.json", "mcpServers", mapOf("type" to "http") + urlEntry(t))
                }
            }),
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
        Client("opencode", "opencode", URI("https://opencode.ai/docs/mcp-servers"), { t ->
            val entry: Map<String, Any?> = when {
                t.stdio -> linkedMapOf("type" to "local", "command" to listOf(t.launch!!.command) + t.launch.args,
                    "enabled" to true) + (if (t.values.isEmpty()) emptyMap() else mapOf("environment" to t.values))
                t.sse -> return@Client null
                else -> linkedMapOf("type" to "remote", "url" to t.url, "enabled" to true) + headers(t)
            }
            Snippet(t.optionId, true, "opencode.json (project) or ~/.config/opencode/opencode.json", "json",
                json(linkedMapOf("\$schema" to "https://opencode.ai/config.json", "mcp" to mapOf(t.key to entry))))
        }),
        Client("gemini-cli", "Gemini CLI", URI("https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/mcp-server.md"), { t ->
            jsonSnippet(t, ".gemini/settings.json (project) or ~/.gemini/settings.json", "mcpServers", when {
                t.stdio -> stdioEntry(t)
                // Gemini CLI: httpUrl is Streamable HTTP, url is SSE.
                t.sse -> mapOf("url" to t.url) + headers(t)
                else -> mapOf("httpUrl" to t.url) + headers(t)
            })
        }),
        Client("zed", "Zed", URI("https://zed.dev/docs/ai/mcp"), { t ->
            when {
                t.stdio -> jsonSnippet(t, "~/.config/zed/settings.json", "context_servers", stdioEntry(t) + ("env" to t.values))
                t.sse -> null
                else -> jsonSnippet(t, "~/.config/zed/settings.json", "context_servers", urlEntry(t))
            }
        }),
        Client("cline", "Cline", URI("https://docs.cline.bot/mcp/mcp-overview"), { t ->
            jsonSnippet(t, "Cline › MCP Servers › Configure (cline_mcp_settings.json)", "mcpServers", when {
                t.stdio -> stdioEntry(t) + linkedMapOf("disabled" to false, "autoApprove" to emptyList<String>())
                // Without a type Cline assumes the legacy SSE transport.
                else -> mapOf("type" to if (t.sse) "sse" else "streamableHttp") + urlEntry(t)
            })
        }),
        Client("continue", "Continue", URI("https://docs.continue.dev/customize/deep-dives/mcp"), { t ->
            val server: Map<String, Any?> = linkedMapOf<String, Any?>("name" to t.key) + if (t.stdio) {
                linkedMapOf("type" to "stdio", "command" to t.launch!!.command, "args" to t.launch.args) +
                    (if (t.values.isEmpty()) emptyMap() else mapOf("env" to t.values))
            } else {
                linkedMapOf("type" to if (t.sse) "sse" else "streamable-http", "url" to t.url) +
                    (if (t.values.isEmpty()) emptyMap() else mapOf("requestOptions" to mapOf("headers" to t.values)))
            }
            Snippet(t.optionId, true, ".continue/mcpServers/${t.key}.yaml", "yaml",
                yaml(linkedMapOf("name" to t.key, "version" to "0.0.1", "schema" to "v1", "mcpServers" to listOf(server))))
        }),
        Client("goose", "Goose", URI("https://goose-docs.ai/docs/guides/config-files"), { t ->
            val ext: Map<String, Any?> = when {
                t.stdio -> linkedMapOf("type" to "stdio", "name" to t.key, "enabled" to true, "cmd" to t.launch!!.command,
                    "args" to t.launch.args, "envs" to t.values, "env_keys" to emptyList<String>(), "timeout" to 300)
                t.sse -> return@Client null
                else -> linkedMapOf("type" to "streamable_http", "name" to t.key, "enabled" to true, "uri" to t.url,
                    "headers" to t.values, "envs" to emptyMap<String, String>(), "env_keys" to emptyList<String>(), "timeout" to 300)
            }
            Snippet(t.optionId, true, "~/.config/goose/config.yaml", "yaml", yaml(mapOf("extensions" to mapOf(t.key to ext))))
        }, { t -> if (t.sse) "Goose does not support SSE servers." else Client.NO_WAY }),
        Client("kiro", "Kiro", URI("https://kiro.dev/docs/mcp/configuration"), { t ->
            when {
                t.stdio -> jsonSnippet(t, ".kiro/settings/mcp.json (project) or ~/.kiro/settings/mcp.json", "mcpServers",
                    stdioEntry(t) + linkedMapOf("disabled" to false, "autoApprove" to emptyList<String>()))
                t.sse -> null
                else -> jsonSnippet(t, ".kiro/settings/mcp.json (project) or ~/.kiro/settings/mcp.json", "mcpServers", urlEntry(t))
            }
        }),
        Client("roo-code", "Roo Code", URI("https://roocodeinc.github.io/Roo-Code/features/mcp/using-mcp-in-roo/"), { t ->
            jsonSnippet(t, ".roo/mcp.json (project) or the global mcp_settings.json", "mcpServers", when {
                t.stdio -> stdioEntry(t) + linkedMapOf("alwaysAllow" to emptyList<String>(), "disabled" to false)
                else -> mapOf("type" to if (t.sse) "sse" else "streamable-http") + urlEntry(t)
            })
        }),
        Client("warp", "Warp", URI("https://docs.warp.dev/agents/capabilities/mcp"), { t ->
            jsonSnippet(t, "~/.warp/.mcp.json or Settings › Agents › MCP servers", "mcpServers",
                if (t.stdio) stdioEntry(t) else urlEntry(t))
        }),
        Client("jetbrains", "JetBrains AI Assistant", URI("https://www.jetbrains.com/help/ai-assistant/mcp.html"), { t ->
            when {
                // The documentation shows neither env nor headers.
                t.values.isNotEmpty() -> null
                else -> jsonSnippet(t, "Settings › Tools › AI Assistant › Model Context Protocol (MCP) › Add › As JSON",
                    "mcpServers", if (t.stdio) stdioEntry(t) else mapOf("url" to t.url))
            }
        }, { t ->
            if (t.stdio) "The documentation does not say how to pass environment variables."
            else "The documentation does not say how to send headers."
        }),
        Client("amazon-q", "Amazon Q Developer CLI",
            URI("https://docs.aws.amazon.com/amazonq/latest/qdeveloper-ug/command-line-mcp-config-CLI.html"), { t ->
                when {
                    t.stdio -> jsonSnippet(t, "~/.aws/amazonq/cli-agents/<agent>.json", "mcpServers", stdioEntry(t))
                    t.sse || t.values.isNotEmpty() -> null
                    else -> jsonSnippet(t, "~/.aws/amazonq/cli-agents/<agent>.json", "mcpServers", mapOf("type" to "http", "url" to t.url))
                }
            }, { t -> if (t.sse) Client.NO_SSE else "The documentation does not say how to send headers." }),
        // The documentation shows only a remote server ("follows Cursor's notation"); a local one is not shown.
        Client("lm-studio", "LM Studio", URI("https://lmstudio.ai/docs/app/mcp"), { t ->
            if (t.stdio || t.sse) null
            else jsonSnippet(t, "LM Studio › Program › Install › Edit mcp.json", "mcpServers", urlEntry(t))
        }, { t -> if (t.sse) Client.NO_SSE else "The documentation shows only remote servers." }),
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
