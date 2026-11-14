package ai.helper2.setup

internal object Formats {

  fun json(value: Any?, indent: String = ""): String =
    when (value) {
      null -> "null"
      is String -> jsonString(value)
      is Boolean,
      is Number -> value.toString()
      is Map<*, *> ->
        if (value.isEmpty()) "{}"
        else
          value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
            "$indent  ${jsonString(k.toString())}: ${json(v, "$indent  ")}"
          }
      is List<*> ->
        when {
          value.isEmpty() -> "[]"
          value.all { it is String } -> value.joinToString(", ", "[", "]") { json(it) }
          else ->
            value.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${json(it, "$indent  ")}" }
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

  private fun escaped(c: Char) =
    c < ' ' || c in '\u007f'..'\u009f' || c == '\u2028' || c == '\u2029' || c == '\ufeff'

  fun tomlKey(k: String): String = if (BARE.matches(k)) k else jsonString(k)

  fun toml(value: Any?): String =
    when (value) {
      is String -> jsonString(value)
      is List<*> -> value.joinToString(", ", "[", "]") { toml(it) }
      is Map<*, *> ->
        value.entries.joinToString(", ", "{ ", " }") { (k, v) ->
          "${tomlKey(k.toString())} = ${toml(v)}"
        }
      else -> value.toString()
    }

  fun sh(word: String): String =
    when {
      SAFE_SHELL.matches(word) -> word
      word.none(::escaped) -> "'" + word.replace("'", "'\\''") + "'"
      else ->
        buildString {
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
