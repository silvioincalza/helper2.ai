package ai.helper2.setup

import java.net.URI
import java.time.Instant

enum class Transport {
  STREAMABLE_HTTP,
  SSE,
  STDIO,
}

enum class SettingKind {
  ENV,
  HEADER,
}

data class Setting(
  val name: String,
  val kind: SettingKind,
  val description: String?,
  val required: Boolean,
  val secret: Boolean,
  val value: String? = null,
)

data class Argument(
  val name: String?,
  val value: String?,
  val hint: String?,
  val description: String?,
  val required: Boolean,
  val secret: Boolean,
)

sealed interface RunOption {
  val transport: Transport
  val settings: List<Setting>

  data class Remote(
    override val transport: Transport,
    val url: URI,
    override val settings: List<Setting>,
  ) : RunOption

  data class Command(
    val command: String,
    val args: List<String>,
    override val settings: List<Setting>,
  ) : RunOption {
    override val transport: Transport
      get() = Transport.STDIO
  }

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

data class McpServerSpec
@JvmOverloads
constructor(
  val name: String,
  val version: String?,
  val options: List<RunOption>,
  val sourceUrl: URI,
  val retrievedAt: Instant,
  val sourceId: String = "mcp-registry",
)

class SetupUnavailable(message: String) : RuntimeException(message)

fun interface ReadmeConfigs {
  fun of(ref: ToolRef): McpServerSpec?

  companion object {
    val NONE = ReadmeConfigs { null }
  }
}

fun interface McpServerSpecs {
  fun latest(name: String): McpServerSpec?
}
