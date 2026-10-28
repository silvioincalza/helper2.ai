package ai.helper2.setup

import java.net.URI
import java.time.Instant

/** How a server is reached, as its registry entry states it. */
enum class Transport { STREAMABLE_HTTP, SSE, STDIO }

enum class SettingKind { ENV, HEADER }

/**
 * An environment variable (a package) or header (a remote) the registry entry declares. [value] is the value the
 * entry publishes (possibly a `{placeholder}` template) or its default; a secret's default is never kept.
 */
data class Setting(
    val name: String,
    val kind: SettingKind,
    val description: String?,
    val required: Boolean,
    val secret: Boolean,
    val value: String? = null,
)

/**
 * One command-line argument the entry declares: a fixed [value], or one the user supplies ([value] null), shown
 * as `<hint>`. A named argument ([name], e.g. `--port`) comes before its value.
 */
data class Argument(
    val name: String?,
    val value: String?,
    val hint: String?,
    val description: String?,
    val required: Boolean,
    val secret: Boolean,
)

/** One way to run a server: a hosted endpoint, or a package launched locally. */
sealed interface RunOption {
    val transport: Transport
    val settings: List<Setting>

    data class Remote(
        override val transport: Transport,
        val url: URI,
        override val settings: List<Setting>,
    ) : RunOption

    /**
     * A local command exactly as a README's own config example states it (`command`, `args`, `env`), used when
     * the server has no registry entry.
     */
    data class Command(
        val command: String,
        val args: List<String>,
        override val settings: List<Setting>,
    ) : RunOption {
        override val transport: Transport get() = Transport.STDIO
    }

    /** A package (npm, PyPI, OCI image, NuGet) run locally; [runtimeArgs] go to the launcher, [packageArgs] to the server. */
    data class Package(
        val registryType: String,
        val identifier: String,
        val version: String?,
        override val transport: Transport,
        val runtimeHint: String?,
        val runtimeArgs: List<Argument>,
        val packageArgs: List<Argument>,
        override val settings: List<Setting>,
    ) : RunOption
}

/** The setup facts of one MCP server: its latest registry entry, with where and when it was read. */
data class McpServerSpec @JvmOverloads constructor(
    val name: String,
    val version: String?,
    val options: List<RunOption>,
    val sourceUrl: URI,
    val retrievedAt: Instant,
    /** "mcp-registry", or "readme" for a config example read from the server's own README. */
    val sourceId: String = "mcp-registry",
)

/** The source of setup facts could not be read now (down, timeout, malformed answer). */
class SetupUnavailable(message: String) : RuntimeException(message)

/**
 * Outbound port: run options from the config examples in a server's own README (an `mcpServers` / `servers`
 * JSON block), or null when it has none. Used only when the server has no registry entry.
 */
fun interface ReadmeConfigs {
    fun of(ref: ToolRef): McpServerSpec?

    companion object {
        val NONE = ReadmeConfigs { null }
    }
}

/** Outbound port: the latest registry entry of an MCP server by its registry name, or null when there is none. */
fun interface McpServerSpecs {
    fun latest(name: String): McpServerSpec?
}
