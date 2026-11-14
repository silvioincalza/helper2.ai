package ai.helper2.setup

data class Launch(
  val command: String,
  val args: List<String>,
  val env: List<Setting>,
  val requires: String,
)

object Launches {
  fun of(p: RunOption.Package): Launch? {
    val type = p.registryType.lowercase()
    val command = p.runtimeHint ?: DEFAULT_RUNTIME[type] ?: return null
    val requires = REQUIRES[command] ?: return null
    val runtime = p.runtimeArgs.flatMap(::tokens)
    val args =
      when {
        runtime.isNotEmpty() -> runtime + packageRef(type, p)
        else -> defaultArgs(type, p)
      } + p.packageArgs.flatMap(::tokens)
    return Launch(command, args, p.settings, requires)
  }

  fun of(c: RunOption.Command): Launch? =
    REQUIRES[c.command]?.let { Launch(c.command, c.args, c.settings, it) }

  fun placeholder(name: String): String = "<" + name + ">"

  private fun defaultArgs(type: String, p: RunOption.Package): List<String> =
    when (type) {
      "npm" -> listOf("-y", packageRef(type, p))
      "oci" ->
        listOf("run", "-i", "--rm") +
          p.settings.filter { it.required || it.value != null }.flatMap { listOf("-e", it.name) } +
          packageRef(type, p)
      "nuget" -> listOf(packageRef(type, p), "--yes")
      else -> listOf(packageRef(type, p))
    }

  private fun packageRef(type: String, p: RunOption.Package): String {
    val v = p.version
    return when {
      v == null -> p.identifier
      type == "npm" || type == "nuget" -> p.identifier + "@" + v
      type == "pypi" -> p.identifier + "==" + v
      type == "oci" ->
        if (p.identifier.substringAfterLast('/').contains(':')) p.identifier
        else p.identifier + ":" + v
      else -> p.identifier
    }
  }

  private fun tokens(a: Argument): List<String> {
    if (a.value == null && !a.required) return emptyList()
    val value = a.value ?: placeholder(a.hint ?: a.name?.trimStart('-') ?: "value")
    return if (a.name != null) listOf(a.name, value) else listOf(value)
  }

  private val DEFAULT_RUNTIME =
    mapOf("npm" to "npx", "pypi" to "uvx", "oci" to "docker", "nuget" to "dnx")
  private val REQUIRES =
    mapOf(
      "npx" to "Node.js (npx)",
      "uvx" to "uv (uvx)",
      "docker" to "Docker",
      "dnx" to ".NET 10 SDK (dnx)",
      "bunx" to "Bun (bunx)",
    )
}
