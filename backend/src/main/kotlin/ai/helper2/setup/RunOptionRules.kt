package ai.helper2.setup

/**
 * What a run option may contain before it becomes a copy-paste snippet. A local command is a package runner
 * (`npx`, `uvx`, `bunx`, `dnx`, or `docker run`) that fetches one named package or image; flags before the package
 * come from a short allow-list per runner, so nothing evaluates inline code, installs from a URL or a path, or
 * reaches the host from a container. Registry identifiers and versions follow their registry's grammar, environment
 * variable and header names their own, and no value holds a control character.
 */
object RunOptionRules {

    /** The package (or image) a local command runs, as written; null when the command is not one we show. */
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

    /** The package name of a [target], without its version, tag, digest or extras. */
    fun packageName(target: String): String {
        val unversioned = if (target.startsWith("@")) "@" + target.drop(1).substringBefore('@') else target.substringBefore('@')
        val name = unversioned.substringBefore("==").substringBefore('[')
        return if (':' in name.substringAfterLast('/')) name.substringBeforeLast(':') else name
    }

    fun validIdentifier(registryType: String, identifier: String): Boolean = when (registryType.lowercase()) {
        "npm" -> NPM_NAME.matches(identifier)
        "pypi" -> PYPI_NAME.matches(identifier)
        "nuget" -> NUGET_NAME.matches(identifier)
        "oci" -> OCI_IMAGE.matches(identifier)
        else -> false
    }

    fun validVersion(version: String): Boolean = VERSION.matches(version)

    /** Whether [option] may be shown at all: names, values and, for anything run locally, its launch. */
    fun accepts(option: RunOption): Boolean {
        if (!option.settings.all(::validSetting)) return false
        return when (option) {
            is RunOption.Remote -> true
            is RunOption.Command -> target(option.command, option.args) != null
            // The launcher part is checked like a README command; package arguments go to the server, as after npx.
            is RunOption.Package -> validIdentifier(option.registryType, option.identifier) &&
                (option.version == null || validVersion(option.version)) &&
                (option.runtimeArgs + option.packageArgs).all(::validArgument) &&
                Launches.of(option.copy(packageArgs = emptyList()))?.let { target(it.command, it.args) } != null
        }
    }

    /**
     * Whether a README config entry named [entryKey] is the resource of [ref] and not another server shown in the
     * same example: the entry's key or the package it runs names the npm package, the repository or the title.
     * Names are compared without case, punctuation or the words "mcp" and "server".
     */
    fun matches(ref: ToolRef, entryKey: String, option: RunOption): Boolean {
        val resource = buildList {
            ref.npmPackage?.let { add(it); add(it.substringAfter('/')) }
            ref.githubRepo?.let { add(it.substringAfter('/')) }
            add(ref.title)
        }.mapNotNull(::core).toSet()
        val entry = buildList {
            add(entryKey)
            if (option is RunOption.Command) {
                target(option.command, option.args)?.let(::packageName)?.let { add(it); add(it.substringAfterLast('/')) }
            }
        }.mapNotNull(::core)
        return entry.any { it in resource }
    }

    /** A name reduced to the words that identify it, or null when nothing is left. */
    private fun core(name: String): String? = name.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() && it !in FILLER }.joinToString("-").ifEmpty { null }

    private val FILLER = setOf("mcp", "server", "servers", "model", "context", "protocol")

    /** A runner's flags (allowed ones, and those taking a value) up to its first positional, then that positional. */
    private class Invocation(val values: List<Pair<String, String>>, val spec: String)

    private fun invocation(args: List<String>, flags: Set<String>, valued: Set<String> = emptySet()): Invocation? {
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
                // The package; what follows goes to the server, not to the runner.
                else -> return Invocation(values, a)
            }
        }
        return null
    }

    private fun uvx(args: List<String>): String? {
        val checks = mapOf<String, (String) -> Boolean>("--from" to ::validPypiSpec, "--with" to ::validPypiSpec,
            "--python" to { PYTHON_VERSION.matches(it) })
        val run = invocation(args, UVX_FLAGS, checks.keys) ?: return null
        if (!validPypiSpec(run.spec) || !run.values.all { (flag, value) -> checks.getValue(flag)(value) }) return null
        return run.values.lastOrNull { it.first == "--from" }?.second ?: run.spec
    }

    /** `docker run [allowed flags] image`: the image is the only positional and nothing follows it. */
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
    private val DOCKER_VALUED: Map<String, (String) -> Boolean> = mapOf(
        "-e" to ::validEnvArg, "--env" to ::validEnvArg,
        // Only a named volume: a host path would expose the user's files to the container.
        "-v" to { NAMED_VOLUME.matches(it) }, "--volume" to { NAMED_VOLUME.matches(it) },
        // Never the host's network or another container's.
        "--network" to { NETWORK.matches(it) && it != "host" }, "--net" to { NETWORK.matches(it) && it != "host" },
        "--name" to { NETWORK.matches(it) },
        "--pull" to { it in setOf("always", "missing", "never") },
    )

    private fun validEnvArg(v: String) = ENV_NAME.matches(v.substringBefore('='))

    private fun validSetting(s: Setting): Boolean =
        (if (s.kind == SettingKind.ENV) ENV_NAME else HEADER_NAME).matches(s.name) && s.value?.let(::hasControl) != true

    private fun validArgument(a: Argument): Boolean =
        (a.name == null || ARGUMENT_NAME.matches(a.name)) && listOfNotNull(a.value, a.hint).none(::hasControl)

    private fun validNpmSpec(spec: String): Boolean {
        val at = spec.indexOf('@', 1)
        return if (at < 0) NPM_NAME.matches(spec) else NPM_NAME.matches(spec.substring(0, at)) && NPM_TAG.matches(spec.substring(at + 1))
    }

    private fun validNugetSpec(spec: String): Boolean =
        NUGET_NAME.matches(spec.substringBefore('@')) && (!spec.contains('@') || VERSION.matches(spec.substringAfter('@')))

    private fun validPypiSpec(spec: String): Boolean = PYPI_SPEC.matches(spec)

    private fun hasControl(s: String) = s.any { it < ' ' || it == '\u007f' }

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    /** RFC 9110 token. */
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val ARGUMENT_NAME = Regex("--?[A-Za-z0-9][A-Za-z0-9_.-]*")
    private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
    private val NPM_TAG = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
    private val NPM_NAME = Regex("(?=.{1,214}$)(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")
    private val PYPI_NAME = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?")
    private val PYPI_SPEC = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?(?:\\[[A-Za-z0-9._,-]+])?(?:(?:==|@)[A-Za-z0-9][A-Za-z0-9._+!-]{0,63})?")
    private val PYTHON_VERSION = Regex("[0-9]+(?:\\.[0-9]+){0,2}")
    private val NUGET_NAME = Regex("[A-Za-z0-9_](?:[A-Za-z0-9_.-]{0,98}[A-Za-z0-9_])?")
    /** The OCI reference grammar: optional registry host, lowercase path, optional tag, optional digest. */
    private val OCI_IMAGE = Regex(
        "(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+|localhost)(?::[0-9]+)?/)?" +
            "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*)*" +
            "(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[a-f0-9]{64})?",
    )
    private val NAMED_VOLUME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*:/[A-Za-z0-9_./-]*(?::(?:ro|rw))?")
    private val NETWORK = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
}
