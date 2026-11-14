package ai.helper2.setup

import java.net.URI
import java.time.Instant

data class ShownOption(
  val id: String,
  val option: RunOption,
  val label: String,
  val launch: Launch?,
)

data class ClientGuide(val client: Client, val snippets: List<Snippet>)

data class SetupSource(val sourceId: String, val url: URI, val retrievedAt: Instant)

data class SetupGuide(
  val title: String,
  val version: String?,
  val options: List<ShownOption>,
  val clients: List<ClientGuide>,
  val sources: List<SetupSource>,
) {
  companion object {
    fun none(title: String) = SetupGuide(title, null, emptyList(), emptyList(), emptyList())
  }
}

class SetupGuides
@JvmOverloads
constructor(
  private val specs: McpServerSpecs,
  private val readme: ReadmeConfigs = ReadmeConfigs.NONE,
  private val clients: List<Client> = Clients.ALL,
) {
  fun build(title: String, registryName: String?, ref: ToolRef? = null): SetupGuide {
    var registryDown: SetupUnavailable? = null
    val fromRegistry =
      try {
        registryName?.let(specs::latest)?.takeIf { it.options.any(RunOptionRules::accepts) }
      } catch (e: SetupUnavailable) {
        registryDown = e
        null
      }
    val spec =
      fromRegistry
        ?: ref
          ?.takeIf { it.type == "MCP_SERVER" && (it.githubRepo != null || it.npmPackage != null) }
          ?.let(readme::of)
        ?: registryDown?.let { throw it }
        ?: return SetupGuide.none(title)
    val shown =
      spec.options
        .filter(RunOptionRules::accepts)
        .mapNotNull { o ->
          when (o) {
            is RunOption.Remote -> o to null
            is RunOption.Package ->
              if (o.transport == Transport.STDIO) Launches.of(o)?.let { o to it } else null
            is RunOption.Command -> Launches.of(o)?.let { o to it }
          }
        }
        .mapIndexed { i, (o, launch) -> ShownOption("o${i + 1}", o, label(o), launch) }
    val key = key(title, spec.name)
    val targets = shown.map { Target(key, it.id, it.option, it.launch, values(it.option)) }
    val guides =
      if (targets.isEmpty()) emptyList()
      else clients.map { c -> ClientGuide(c, targets.map(c::snippet)) }
    return SetupGuide(
      title,
      spec.version,
      shown,
      guides,
      listOf(SetupSource(spec.sourceId, spec.sourceUrl, spec.retrievedAt)),
    )
  }

  companion object {
    fun key(title: String, registryName: String): String {
      val slug = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
      return slug(title)
        .ifEmpty { slug(registryName.substringAfterLast('/')) }
        .ifEmpty { "server" }
        .take(40)
        .trimEnd('-')
    }

    fun values(o: RunOption): Map<String, String> =
      o.settings.filter { it.required || it.value != null }.associate { it.name to shownValue(it) }

    @JvmStatic
    fun shownValue(s: Setting): String {
      val v = s.value ?: return Launches.placeholder(s.name)
      return when {
        TEMPLATE.containsMatchIn(v) ->
          TEMPLATE.replace(v) { Launches.placeholder(it.groupValues[1]) }
        s.secret -> Launches.placeholder(s.name)
        else -> v
      }
    }

    fun secretValue(s: Setting, secret: String): String {
      val template = s.value?.takeIf { TEMPLATE.containsMatchIn(it) } ?: return secret
      return TEMPLATE.replace(template) { secret }
    }

    private val TEMPLATE = Regex("\\{([A-Za-z0-9_.-]+)\\}")

    private fun label(o: RunOption): String =
      when (o) {
        is RunOption.Remote ->
          "Hosted server (" + (if (o.transport == Transport.SSE) "SSE" else "Streamable HTTP") + ")"
        is RunOption.Package -> o.registryType + " package " + o.identifier
        is RunOption.Command -> "Local command " + o.command + " (from the README)"
      }
  }
}
