package ai.helper2.setup

object RunOptionRules {
  fun target(command: String, args: List<String>): String? {
    if (args.any(::hasControl)) return null
    return when (command) {
      "npx" -> invocation(args, NPX_FLAGS)?.spec?.takeIf(::validNpmSpec)
      "bunx" -> invocation(args, BUNX_FLAGS)?.spec?.takeIf(::validNpmSpec)
      "dnx" -> invocation(args, DNX_FLAGS)?.spec?.takeIf(::validNugetSpec)
      "uvx" -> uvx(args)
      "docker" -> docker(args)
      else -> null
    }
  }

  fun packageName(target: String): String {
    val unversioned =
      if (target.startsWith("@")) "@" + target.drop(1).substringBefore('@')
      else target.substringBefore('@')
    val name = unversioned.substringBefore("==").substringBefore('[')
    return if (':' in name.substringAfterLast('/')) name.substringBeforeLast(':') else name
  }

  fun validIdentifier(registryType: String, identifier: String): Boolean =
    when (registryType.lowercase()) {
      "npm" -> NPM_NAME.matches(identifier)
      "pypi" -> PYPI_NAME.matches(identifier)
      "nuget" -> NUGET_NAME.matches(identifier)
      "oci" -> OCI_IMAGE.matches(identifier)
      else -> false
    }

  fun validVersion(version: String): Boolean = VERSION.matches(version)

  fun accepts(option: RunOption): Boolean {
    if (!option.settings.all(::validSetting)) return false
    return when (option) {
      is RunOption.Remote -> true
      is RunOption.Command -> target(option.command, option.args) != null
      is RunOption.Package ->
        validIdentifier(option.registryType, option.identifier) &&
          (option.version == null || validVersion(option.version)) &&
          (option.runtimeArgs + option.packageArgs).all(::validArgument) &&
          Launches.of(option.copy(packageArgs = emptyList()))?.let {
            target(it.command, it.args)
          } != null
    }
  }

  fun matches(ref: ToolRef, entryKey: String, option: RunOption): Boolean {
    val resource = buildList {
      ref.npmPackage?.let {
        add(it)
        add(it.substringAfter('/'))
      }
      ref.githubRepo?.let { add(it.substringAfter('/')) }
      add(ref.title)
    }
      .mapNotNull(::core)
      .toSet()
    val entry = buildList {
      add(entryKey)
      if (option is RunOption.Command) {
        target(option.command, option.args)?.let(::packageName)?.let {
          add(it)
          add(it.substringAfterLast('/'))
        }
      }
    }
      .mapNotNull(::core)
    return entry.any { it in resource }
  }

  private fun core(name: String): String? =
    name
      .lowercase()
      .split(Regex("[^a-z0-9]+"))
      .filter { it.isNotEmpty() && it !in FILLER }
      .joinToString("-")
      .ifEmpty { null }

  private val FILLER = setOf("mcp", "server", "servers", "model", "context", "protocol")

  private class Invocation(val values: List<Pair<String, String>>, val spec: String)

  private fun invocation(
    args: List<String>,
    flags: Set<String>,
    valued: Set<String> = emptySet(),
  ): Invocation? {
    val values = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i < args.size) {
      val a = args[i]
      when {
        a in flags -> i++
        a in valued && i + 1 < args.size -> {
          values += a to args[i + 1]
          i += 2
        }
        a.startsWith("-") -> return null
        else -> return Invocation(values, a)
      }
    }
    return null
  }

  private fun uvx(args: List<String>): String? {
    val checks =
      mapOf<String, (String) -> Boolean>(
        "--from" to ::validPypiSpec,
        "--with" to ::validPypiSpec,
        "--python" to { PYTHON_VERSION.matches(it) },
      )
    val run = invocation(args, UVX_FLAGS, checks.keys) ?: return null
    if (
      !validPypiSpec(run.spec) || !run.values.all { (flag, value) -> checks.getValue(flag)(value) }
    )
      return null
    return run.values.lastOrNull { it.first == "--from" }?.second ?: run.spec
  }

  private fun docker(args: List<String>): String? {
    if (args.firstOrNull() != "run") return null
    var image: String? = null
    var i = 1
    while (i < args.size) {
      val a = args[i]
      if (image != null) return null
      if (!a.startsWith("-")) {
        image = a.takeIf { OCI_IMAGE.matches(it) } ?: return null
        i++
        continue
      }
      if (a in DOCKER_FLAGS) {
        i++
        continue
      }
      val name = if (a.startsWith("--") && '=' in a) a.substringBefore('=') else a
      val check = DOCKER_VALUED[name] ?: return null
      val value = if (name != a) a.substringAfter('=') else args.getOrNull(++i) ?: return null
      if (!check(value)) return null
      i++
    }
    return image
  }

  private val NPX_FLAGS = setOf("-y", "--yes", "-q", "--quiet")
  private val BUNX_FLAGS = setOf("--bun")
  private val DNX_FLAGS = setOf("-y", "--yes")
  private val UVX_FLAGS = setOf("-q", "--quiet", "--isolated")
  private val DOCKER_FLAGS = setOf("-i", "--interactive", "--rm", "-t", "--tty", "-it", "--init")
  private val DOCKER_VALUED: Map<String, (String) -> Boolean> =
    mapOf(
      "-e" to ::validEnvArg,
      "--env" to ::validEnvArg,
      "-v" to { NAMED_VOLUME.matches(it) },
      "--volume" to { NAMED_VOLUME.matches(it) },
      "--network" to { NETWORK.matches(it) && it != "host" },
      "--net" to { NETWORK.matches(it) && it != "host" },
      "--name" to { NETWORK.matches(it) },
      "--pull" to { it in setOf("always", "missing", "never") },
    )

  private fun validEnvArg(v: String) = ENV_NAME.matches(v.substringBefore('='))

  private fun validSetting(s: Setting): Boolean =
    (if (s.kind == SettingKind.ENV) ENV_NAME else HEADER_NAME).matches(s.name) &&
      s.value?.let(::hasControl) != true

  private fun validArgument(a: Argument): Boolean =
    (a.name == null || ARGUMENT_NAME.matches(a.name)) &&
      listOfNotNull(a.value, a.hint).none(::hasControl)

  private fun validNpmSpec(spec: String): Boolean {
    val at = spec.indexOf('@', 1)
    return if (at < 0) NPM_NAME.matches(spec)
    else NPM_NAME.matches(spec.substring(0, at)) && NPM_TAG.matches(spec.substring(at + 1))
  }

  private fun validNugetSpec(spec: String): Boolean =
    NUGET_NAME.matches(spec.substringBefore('@')) &&
      (!spec.contains('@') || VERSION.matches(spec.substringAfter('@')))

  private fun validPypiSpec(spec: String): Boolean = PYPI_SPEC.matches(spec)

  private fun hasControl(s: String) = s.any { it < ' ' || it == '\u007f' }

  private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
  private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
  private val ARGUMENT_NAME = Regex("--?[A-Za-z0-9][A-Za-z0-9_.-]*")
  private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
  private val NPM_TAG = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
  private val NPM_NAME = Regex("(?=.{1,214}$)(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")
  private val PYPI_NAME = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?")
  private val PYPI_SPEC =
    Regex(
      "[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?(?:\\[[A-Za-z0-9._,-]+])?(?:(?:==|@)[A-Za-z0-9][A-Za-z0-9._+!-]{0,63})?"
    )
  private val PYTHON_VERSION = Regex("[0-9]+(?:\\.[0-9]+){0,2}")
  private val NUGET_NAME = Regex("[A-Za-z0-9_](?:[A-Za-z0-9_.-]{0,98}[A-Za-z0-9_])?")
  private val OCI_IMAGE =
    Regex(
      "(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+|localhost)(?::[0-9]+)?/)?" +
        "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*)*" +
        "(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[a-f0-9]{64})?"
    )
  private val NAMED_VOLUME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*:/[A-Za-z0-9_./-]*(?::(?:ro|rw))?")
  private val NETWORK = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
}
