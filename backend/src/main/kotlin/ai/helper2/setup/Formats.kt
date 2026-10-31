package ai.helper2.setup

/**
 * Minimal writers for the config formats AI clients read (JSON, TOML, POSIX shell). The domain stays free of
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
     * Characters written as `\uXXXX` in JSON and TOML strings: controls (C0, DEL, C1), which TOML does not allow raw,
     * the line and paragraph separators some readers fold as line breaks, and the byte order mark.
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
