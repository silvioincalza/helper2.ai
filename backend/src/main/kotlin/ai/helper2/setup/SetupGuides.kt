package ai.helper2.setup

import java.net.URI
import java.time.Instant

/** A run option as shown to the user, with the stable id snippets refer to. */
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

/**
 * Builds the setup guide of a resource from its source data only: an MCP server's latest entry in the Official MCP
 * Registry, else the config example in its README. An option that [RunOptionRules] does not accept, a package whose
 * launcher cannot be stated, and a package not run over stdio are left out rather than guessed.
 */
class SetupGuides @JvmOverloads constructor(
    private val specs: McpServerSpecs,
    private val readme: ReadmeConfigs = ReadmeConfigs.NONE,
    private val clients: List<Client> = Clients.ALL,
) {

    /** [registryName] is the server's name in the MCP Registry, or null when the resource has no registry record. */
    fun build(title: String, registryName: String?): SetupGuide = build(title, registryName, null)

    /**
     * The registry entry first; without one (or one with no usable option), the config example in the README of
     * [ref], when the resource is an MCP server. When the registry cannot be read, the README example answers;
     * without one, [SetupUnavailable] is thrown, since the registry may well have an entry.
     */
    fun build(title: String, registryName: String?, ref: ToolRef?): SetupGuide {
        var registryDown: SetupUnavailable? = null
        val fromRegistry = try {
            registryName?.let(specs::latest)?.takeIf { it.options.any(RunOptionRules::accepts) }
        } catch (e: SetupUnavailable) {
            registryDown = e
            null
        }
        val spec = fromRegistry
            ?: ref?.takeIf { it.type == "MCP_SERVER" && (it.githubRepo != null || it.npmPackage != null) }?.let(readme::of)
            ?: registryDown?.let { throw it }
            ?: return SetupGuide.none(title)
        val shown = spec.options.filter(RunOptionRules::accepts).mapNotNull { o ->
            when (o) {
                is RunOption.Remote -> o to null
                is RunOption.Package -> if (o.transport == Transport.STDIO) Launches.of(o)?.let { o to it } else null
                is RunOption.Command -> Launches.of(o)?.let { o to it }
            }
        }.mapIndexed { i, (o, launch) -> ShownOption("o${i + 1}", o, label(o), launch) }
        val key = key(title, spec.name)
        val targets = shown.map { Target(key, it.id, it.option, it.launch, values(it.option)) }
        val guides = if (targets.isEmpty()) emptyList() else clients.map { c -> ClientGuide(c, targets.map(c::snippet)) }
        return SetupGuide(title, spec.version, shown, guides, listOf(SetupSource(spec.sourceId, spec.sourceUrl, spec.retrievedAt)))
    }

    companion object {
        /** The name the server gets in each client's config: from the title, else the registry name. */
        fun key(title: String, registryName: String): String {
            val slug = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            return slug(title).ifEmpty { slug(registryName.substringAfterLast('/')) }.ifEmpty { "server" }.take(40).trimEnd('-')
        }

        /** Required settings and those with a published value; an optional one without a value is left to the user. */
        fun values(o: RunOption): Map<String, String> = o.settings
            .filter { it.required || it.value != null }
            .associate { it.name to shownValue(it) }

        /**
         * A published value with `{name}` parts (e.g. `Bearer {api_key}`) keeps its shape, each part becoming a
         * `<name>` placeholder; a secret is never filled in otherwise.
         */
        @JvmStatic
        fun shownValue(s: Setting): String {
            val v = s.value ?: return Launches.placeholder(s.name)
            return when {
                TEMPLATE.containsMatchIn(v) -> TEMPLATE.replace(v) { Launches.placeholder(it.groupValues[1]) }
                s.secret -> Launches.placeholder(s.name)
                else -> v
            }
        }

        private val TEMPLATE = Regex("\\{([A-Za-z0-9_.-]+)\\}")

        private fun label(o: RunOption): String = when (o) {
            is RunOption.Remote -> "Hosted server (" + (if (o.transport == Transport.SSE) "SSE" else "Streamable HTTP") + ")"
            is RunOption.Package -> o.registryType + " package " + o.identifier
            is RunOption.Command -> "Local command " + o.command + " (from the README)"
        }
    }
}
