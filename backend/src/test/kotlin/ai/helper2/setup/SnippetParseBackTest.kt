package ai.helper2.setup

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.toml.TomlMapper

/**
 * Every snippet of every client, parsed back with a real parser for its format, gives exactly the values it was
 * built from, whatever characters they hold. The writers are hand-rolled (the domain has no serialization
 * library), so a string compare alone would not catch a broken escape.
 */
class SnippetParseBackTest {

    private val hostile = listOf(
        "plain",
        "quote \" and back\\slash",
        "single ' quote and \$(id) `id` \${HOME}",
        "new\nline\r\ttab",
        "nul\u0000 bell\u0007 esc\u001b del\u007f c1\u0085\u009f",
        "toml ]] [[x]] # comment = \"\"\" '''",
        "json: [a, b] {c: d} & * ! | > % @ ` #x",
        "unicode é ☃ 🚀     ﻿",
        "",
        " leading and trailing ",
    )

    private val keys = listOf("PLAIN_KEY", "dotted.key", "quoted\"key", "space key", "x-api-key")

    private val values: Map<String, String> = keys.zip(hostile).toMap() + mapOf("LAST" to hostile.last())

    private val command = Launch("npx", listOf("-y", "@acme/pg-mcp@1.0.0") + hostile, emptyList(), "Node.js (npx)")
    private val stdio = Target("pg", "o1", RunOption.Command("npx", command.args, emptyList()), command, values)
    private val remote = Target("pg", "o2",
        RunOption.Remote(Transport.STREAMABLE_HTTP, URI("https://mcp.acme.dev/mcp?a=1&b=%22"), emptyList()), null, values)

    private val json: ObjectMapper = JsonMapper.builder().build()
    private val toml: ObjectMapper = TomlMapper.builder().build()

    @Test
    fun `every client's snippet parses back to the same arguments, values and url`() {
        for (client in Clients.ALL) {
            for (t in listOf(stdio, remote)) {
                val s = client.snippet(t)
                if (!s.supported) continue
                val text = s.text!!
                val where = "${client.id} ${t.optionId}"
                val strings = when (s.language) {
                    "json" -> leaves(json.readTree(text))
                    "toml" -> leaves(toml.readTree(text))
                    "shell" -> shellWords(text)
                    null -> listOf(text)
                    else -> error("$where: unexpected language ${s.language}")
                }
                if (t.stdio) {
                    assertTrue(strings.containsAll(command.args) || strings.windowed(command.args.size).contains(command.args),
                        "$where: arguments\n$text")
                } else {
                    assertTrue(t.url in strings, "$where: url\n$text")
                }
                // The desktop remote is only a URL.
                if (s.language != null) {
                    for ((k, v) in values) {
                        assertTrue(v in strings || "$k=$v" in strings || "$k: $v" in strings, "$where: value of $k\n$text")
                    }
                }
            }
        }
    }

    @Test
    fun `keys that cannot be bare are quoted and read back as written`() {
        for (client in Clients.ALL.filter { it.id in setOf("codex", "cursor") }) {
            val s = client.snippet(stdio)
            val tree = if (s.language == "toml") toml.readTree(s.text!!) else json.readTree(s.text!!)
            val names = mutableSetOf<String>()
            fun walk(n: JsonNode) {
                n.properties().forEach { (k, v) -> names += k; walk(v) }
                if (n.isArray) n.forEach(::walk)
            }
            walk(tree)
            assertTrue(names.containsAll(keys), "${client.id}: $names\n${s.text}")
        }
    }

    @Test
    fun `toml and json escape every control character`() {
        val all = (0..0x9f).filter { it < 0x20 || it in 0x7f..0x9f }.map { it.toChar() }.joinToString("")
        assertEquals(all, toml.readTree("v = " + Formats.toml(all)).path("v").asString())
        assertEquals(all, json.readTree(Formats.json(all)).asString())
    }

    /**
     * The parsers above accept more than their specs allow, so the written text is also checked against the specs:
     * TOML 1.0 basic strings forbid raw U+007F and controls; C1 controls and U+FEFF are escaped as well.
     */
    @Test
    fun `no snippet holds a raw character its format forbids`() {
        val forbidden = { c: Char -> (c < ' ' && c != '\n') || c in '\u007f'..'\u009f' || c == '\ufeff' }
        for (client in Clients.ALL) {
            for (t in listOf(stdio, remote)) {
                val text = client.snippet(t).text ?: continue
                val bad = text.filter(forbidden).map { "U+%04X".format(it.code) }.distinct()
                assertTrue(bad.isEmpty(), "${client.id} ${t.optionId}: raw $bad")
            }
        }
    }

    private fun leaves(n: JsonNode): List<String> = when {
        n.isString -> listOf(n.asString())
        n.isContainer -> n.flatMap(::leaves) + n.properties().flatMap { leaves(it.value) }
        else -> emptyList()
    }.distinct()

    /** POSIX words as [Formats.sh] writes them: bare, or single-quoted with `'\''` for a quote. */
    private fun shellWords(text: String): List<String> {
        val out = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '$' && text.getOrNull(i + 1) == '\'' -> {
                    i += 2
                    while (text[i] != '\'') {
                        if (text[i] == '\\') {
                            val e = text[i + 1]
                            when (e) {
                                'x' -> { word.append(text.substring(i + 2, i + 4).toInt(16).toChar()); i += 2 }
                                'u' -> { word.append(text.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                                else -> word.append(e)
                            }
                            i += 2
                        } else {
                            word.append(text[i++])
                        }
                    }
                    inWord = true
                }
                c == '\'' -> {
                    val end = text.indexOf('\'', i + 1)
                    word.append(text, i + 1, end)
                    inWord = true
                    i = end
                }
                c == '\\' -> {
                    word.append(text[i + 1])
                    inWord = true
                    i++
                }
                c == ' ' -> {
                    if (inWord) out += word.toString()
                    word.clear()
                    inWord = false
                }
                else -> {
                    assertTrue(c.isLetterOrDigit() || c in "_@%+=:,./-", "unquoted special character ${c.code} in\n$text")
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) out += word.toString()
        return out
    }
}
