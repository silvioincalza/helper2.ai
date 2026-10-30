package ai.helper2.setup

/**
 * Minimal writers for the config formats AI clients read (JSON, TOML, YAML, POSIX shell). The domain stays free of
 * serialization libraries; values are only strings, booleans, numbers, lists and ordered maps.
 */
internal object Formats {

    fun json(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is String -> jsonString(value)
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
            "$indent  ${jsonString(k.toString())}: ${json(v, "$indent  ")}"
        }
        is List<*> -> when {
            value.isEmpty() -> "[]"
            value.all { it is String } -> value.joinToString(", ", "[", "]") { json(it) }
            else -> value.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${json(it, "$indent  ")}" }
        }
        else -> jsonString(value.toString())
    }

    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                escaped(c) -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * Characters written as `\uXXXX` in JSON, TOML and YAML strings: controls (C0, DEL, C1), which TOML and YAML do not
     * allow raw, the line and paragraph separators a YAML 1.1 reader folds as line breaks, and the byte order mark.
     */
    private fun escaped(c: Char) = c < ' ' || c in '\u007f'..'\u009f' || c == '\u2028' || c == '\u2029' || c == '\ufeff'

    /** A TOML key: bare when it can be, quoted otherwise. */
    fun tomlKey(k: String): String = if (BARE.matches(k)) k else jsonString(k)

    /** A TOML inline value; basic strings use the same escapes as JSON. */
    fun toml(value: Any?): String = when (value) {
        is String -> jsonString(value)
        is List<*> -> value.joinToString(", ", "[", "]") { toml(it) }
        is Map<*, *> -> value.entries.joinToString(", ", "{ ", " }") { (k, v) -> "${tomlKey(k.toString())} = ${toml(v)}" }
        else -> value.toString()
    }

    /** YAML block; every string is double-quoted (a valid YAML scalar with JSON escapes), so nothing is misread. */
    fun yaml(value: Map<String, Any?>, indent: String = ""): String = value.entries.joinToString("\n") { (k, v) ->
        val key = "$indent${yamlKey(k)}:"
        when (v) {
            is Map<*, *> -> if (v.isEmpty()) "$key {}" else "$key\n" + yaml(stringKeys(v), "$indent  ")
            is List<*> -> when {
                v.isEmpty() -> "$key []"
                v.all { it !is Map<*, *> } -> "$key " + v.joinToString(", ", "[", "]") { yamlScalar(it) }
                else -> "$key\n" + v.joinToString("\n") { item ->
                    val body = yaml(stringKeys(item as Map<*, *>), "$indent    ")
                    "$indent  - " + body.removePrefix("$indent    ")
                }
            }
            else -> "$key ${yamlScalar(v)}"
        }
    }

    private fun yamlKey(k: String) = if (BARE.matches(k)) k else jsonString(k)

    private fun yamlScalar(v: Any?): String = when (v) {
        null -> "null"
        is String -> jsonString(v)
        else -> v.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringKeys(m: Map<*, *>): Map<String, Any?> = m as Map<String, Any?>

    /**
     * One shell word: bare when it has no character a POSIX shell treats specially, single-quoted otherwise. A word
     * with a character a terminal would act on when pasted (a control, a line break) is written with `$'…'`
     * escapes (POSIX.1-2024, bash, zsh, ksh) so the pasted text holds only printable characters.
     */
    fun sh(word: String): String = when {
        SAFE_SHELL.matches(word) -> word
        word.none(::escaped) -> "'" + word.replace("'", "'\\''") + "'"
        else -> buildString {
            append("$'")
            for (c in word) {
                when {
                    c == '\'' || c == '\\' -> append('\\').append(c)
                    c.code < 0x100 && escaped(c) -> append("\\x%02x".format(c.code))
                    escaped(c) -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('\'')
        }
    }

    private val BARE = Regex("[A-Za-z0-9_-]+")
    private val SAFE_SHELL = Regex("[A-Za-z0-9_@%+=:,./-]+")
}
