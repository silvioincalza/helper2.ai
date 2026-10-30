package ai.helper2.setup

/**
 * The local command that starts a package server, built only from its registry entry: the launcher each registry
 * type uses (or the entry's own `runtimeHint`), the entry's runtime arguments, the package at its version, then
 * the entry's package arguments. A value the user supplies is a `<placeholder>`; a secret is never filled in.
 */
data class Launch(
    val command: String,
    val args: List<String>,
    val env: List<Setting>,
    /** What must be installed for [command] to exist, e.g. "Node.js (npx)". */
    val requires: String,
)

object Launches {

    /** The launch of [p], or null when its registry type has no launcher we can state (e.g. an unknown type). */
    fun of(p: RunOption.Package): Launch? {
        val type = p.registryType.lowercase()
        val command = p.runtimeHint ?: DEFAULT_RUNTIME[type] ?: return null
        val requires = REQUIRES[command] ?: return null
        val runtime = p.runtimeArgs.flatMap(::tokens)
        val args = when {
            runtime.isNotEmpty() -> runtime + packageRef(type, p)
            else -> defaultArgs(type, p)
        } + p.packageArgs.flatMap(::tokens)
        return Launch(command, args, p.settings, requires)
    }

    /** A README's own command: launched as written, or null when its program is not a package runner we know. */
    fun of(c: RunOption.Command): Launch? = REQUIRES[c.command]?.let { Launch(c.command, c.args, c.settings, it) }

    /** `<hint>` stands for a value the user supplies. */
    fun placeholder(name: String): String = "<" + name + ">"

    private fun defaultArgs(type: String, p: RunOption.Package): List<String> = when (type) {
        "npm" -> listOf("-y", packageRef(type, p))
        // OCI: environment variables must be passed into the container one by one.
        "oci" -> listOf("run", "-i", "--rm") + p.settings.filter { it.required || it.value != null }.flatMap { listOf("-e", it.name) } + packageRef(type, p)
        "nuget" -> listOf(packageRef(type, p), "--yes")
        else -> listOf(packageRef(type, p))
    }

    private fun packageRef(type: String, p: RunOption.Package): String {
        val v = p.version
        return when {
            v == null -> p.identifier
            type == "npm" || type == "nuget" -> p.identifier + "@" + v
            type == "pypi" -> p.identifier + "==" + v
            // An OCI identifier usually carries its tag already.
            type == "oci" -> if (p.identifier.substringAfterLast('/').contains(':')) p.identifier else p.identifier + ":" + v
            else -> p.identifier
        }
    }

    private fun tokens(a: Argument): List<String> {
        // An optional argument without a value is left out; the user adds it only if they want it.
        if (a.value == null && !a.required) return emptyList()
        val value = a.value ?: placeholder(a.hint ?: a.name?.trimStart('-') ?: "value")
        return if (a.name != null) listOf(a.name, value) else listOf(value)
    }

    private val DEFAULT_RUNTIME = mapOf("npm" to "npx", "pypi" to "uvx", "oci" to "docker", "nuget" to "dnx")
    private val REQUIRES = mapOf(
        "npx" to "Node.js (npx)",
        "uvx" to "uv (uvx)",
        "docker" to "Docker",
        "dnx" to ".NET 10 SDK (dnx)",
        "bunx" to "Bun (bunx)",
    )
}
