package ai.helper2.setup

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupGuidesTest {

    private val at = Instant.parse("2026-10-27T20:00:00Z")
    private val src = URI("https://registry.modelcontextprotocol.io/v0.1/servers/x/versions/latest")

    private val remote = RunOption.Remote(Transport.STREAMABLE_HTTP, URI("https://mcp.dotsy.ai/mcp"), emptyList())
    private val sse = RunOption.Remote(Transport.SSE, URI("https://example.com/sse"),
        listOf(Setting("Authorization", SettingKind.HEADER, "API key", true, true, "Bearer {api_key}")))
    private val npm = RunOption.Package("npm", "@kaiva/postgres-mcp", "0.1.1", Transport.STDIO, null, emptyList(),
        listOf(Argument(null, null, "database_url", "PostgreSQL URL", required = true, secret = true)),
        listOf(
            Setting("DATABASE_URL", SettingKind.ENV, "Connection", required = true, secret = true, value = null),
            Setting("LOG_LEVEL", SettingKind.ENV, null, required = false, secret = false, value = "info"),
            Setting("OPTIONAL", SettingKind.ENV, null, required = false, secret = false, value = null),
        ))

    private fun guide(vararg options: RunOption) =
        SetupGuides({ McpServerSpec(it, "1.0.0", options.toList(), src, at) }).build("Dotsy", "ai.dotsy/mcp")

    private fun snippet(g: SetupGuide, client: String, option: String = "o1") =
        g.clients.single { it.client.id == client }.snippets.single { it.optionId == option }

    @Test
    fun `no registry record gives no options and no clients`() {
        val g = SetupGuides({ error("not called") }).build("Thing", null)
        assertTrue(g.options.isEmpty())
        assertTrue(g.clients.isEmpty())
        assertTrue(g.sources.isEmpty())
    }

    @Test
    fun `registry down falls back to the README config example`() {
        val readmeSrc = URI("https://github.com/acme/pg-mcp")
        val readme = ReadmeConfigs { McpServerSpec("pg-mcp", null, listOf(remote), readmeSrc, at) }
        val ref = ToolRef("PG", "MCP_SERVER", "acme/pg-mcp", null, null, null)

        val g = SetupGuides({ throw SetupUnavailable("down") }, readme).build("PG", "io.acme/pg", ref)

        assertEquals(listOf(readmeSrc), g.sources.map { it.url })
        assertEquals(1, g.options.size)
    }

    @Test
    fun `registry down and no README example is still unavailable`() {
        val ref = ToolRef("PG", "MCP_SERVER", "acme/pg-mcp", null, null, null)
        assertFailsWith<SetupUnavailable> {
            SetupGuides({ throw SetupUnavailable("down") }, ReadmeConfigs.NONE).build("PG", "io.acme/pg", ref)
        }
    }

    @Test
    fun `npm launch uses npx at the stated version, secrets as placeholders, optional unset values left out`() {
        val l = Launches.of(npm)!!
        assertEquals("npx", l.command)
        assertEquals(listOf("-y", "@kaiva/postgres-mcp@0.1.1", "<database_url>"), l.args)
        assertEquals("Node.js (npx)", l.requires)
        assertEquals(mapOf("DATABASE_URL" to "<DATABASE_URL>", "LOG_LEVEL" to "info"), SetupGuides.values(npm))
    }

    @Test
    fun `pypi uses uvx, oci passes env into docker, unknown registry type has no launch`() {
        val py = npm.copy(registryType = "pypi", identifier = "mcp-pg", version = "2.0", packageArgs = emptyList())
        assertEquals(listOf("mcp-pg==2.0"), Launches.of(py)!!.args)
        val oci = npm.copy(registryType = "oci", identifier = "ghcr.io/a/b:1.2", packageArgs = emptyList())
        assertEquals("docker", Launches.of(oci)!!.command)
        assertEquals(listOf("run", "-i", "--rm", "-e", "DATABASE_URL", "-e", "LOG_LEVEL", "ghcr.io/a/b:1.2"),
            Launches.of(oci)!!.args)
        assertNull(Launches.of(npm.copy(registryType = "cargo")))
    }

    @Test
    fun `runtimeHint and runtime arguments come from the entry`() {
        val p = npm.copy(runtimeHint = "npx", runtimeArgs = listOf(Argument(null, "--stdio", null, null, true, false)),
            packageArgs = emptyList())
        assertEquals(listOf("--stdio", "@kaiva/postgres-mcp@0.1.1"), Launches.of(p)!!.args)
    }

    @Test
    fun `a registry package with an option-like identifier, a bad version or launcher flags that reach the host is dropped`() {
        val hostile = listOf(
            npm.copy(identifier = "-c"),
            npm.copy(identifier = "@acme/x", version = "1 --call rm"),
            npm.copy(runtimeHint = "node"),
            npm.copy(runtimeHint = "npx", runtimeArgs = listOf(Argument(null, "-c", null, null, true, false))),
            npm.copy(registryType = "oci", identifier = "acme/x", runtimeArgs = listOf(Argument(null, "run", null, null, true, false),
                Argument("-v", "/:/host", null, null, true, false))),
            npm.copy(registryType = "oci", identifier = "acme/x", runtimeArgs = listOf(Argument(null, "run", null, null, true, false),
                Argument(null, "--privileged", null, null, true, false))),
            npm.copy(registryType = "cargo", runtimeHint = "npx"),
            npm.copy(settings = listOf(Setting("API-KEY", SettingKind.ENV, null, true, true))),
            sse.copy(settings = listOf(Setting("X Api", SettingKind.HEADER, null, true, true))),
        )
        val g = guide(*(hostile + remote).toTypedArray())
        assertEquals(listOf(remote), g.options.map { it.option })
    }

    @Test
    fun `header template keeps its shape with a placeholder`() {
        assertEquals(mapOf("Authorization" to "Bearer <api_key>"), SetupGuides.values(sse))
    }

    @Test
    fun `every client answers every option, with a snippet or a reason`() {
        val g = guide(remote, sse, npm)
        assertEquals(Clients.ALL.size, g.clients.size)
        for (c in g.clients) {
            assertEquals(listOf("o1", "o2", "o3"), c.snippets.map { it.optionId })
            for (s in c.snippets) {
                assertTrue(if (s.supported) s.text != null else s.note != null, "${c.client.id} ${s.optionId}")
            }
        }
    }

    @Test
    fun `no snippet ever contains a secret default`() {
        val leaky = npm.copy(settings = listOf(Setting("TOKEN", SettingKind.ENV, null, true, true, "sk-live-123")))
        val g = guide(leaky)
        g.clients.flatMap { it.snippets }.mapNotNull { it.text }.forEach { assertFalse("sk-live-123" in it, it) }
    }

    @Test
    fun `claude code commands`() {
        val g = guide(remote, sse, npm)
        assertEquals("claude mcp add --transport http dotsy https://mcp.dotsy.ai/mcp", snippet(g, "claude-code").text)
        assertEquals("claude mcp add --transport sse dotsy https://example.com/sse --header 'Authorization: Bearer <api_key>'",
            snippet(g, "claude-code", "o2").text)
        assertEquals("claude mcp add --env 'DATABASE_URL=<DATABASE_URL>' --env LOG_LEVEL=info --transport stdio dotsy -- " +
            "npx -y @kaiva/postgres-mcp@0.1.1 '<database_url>'", snippet(g, "claude-code", "o3").text)
    }

    @Test
    fun `cursor and claude desktop json`() {
        val g = guide(remote, npm)
        assertEquals("""
            {
              "mcpServers": {
                "dotsy": {
                  "url": "https://mcp.dotsy.ai/mcp"
                }
              }
            }
        """.trimIndent(), snippet(g, "cursor").text)
        assertEquals("""
            {
              "mcpServers": {
                "dotsy": {
                  "command": "npx",
                  "args": ["-y", "@kaiva/postgres-mcp@0.1.1", "<database_url>"],
                  "env": {
                    "DATABASE_URL": "<DATABASE_URL>",
                    "LOG_LEVEL": "info"
                  }
                }
              }
            }
        """.trimIndent(), snippet(g, "claude-desktop", "o2").text)
        val desktopRemote = snippet(g, "claude-desktop")
        assertTrue(desktopRemote.supported)
        assertEquals("https://mcp.dotsy.ai/mcp", desktopRemote.text)
    }

    @Test
    fun `vs code asks for secrets with an input`() {
        val text = snippet(guide(npm), "vscode").text!!
        assertTrue("\"inputs\"" in text && "\"password\": true" in text, text)
        assertTrue("\"DATABASE_URL\": \"\${input:database-url}\"" in text, text)
        assertTrue("\"type\": \"stdio\"" in text, text)
    }

    @Test
    fun `codex toml, gemini httpUrl vs url, opencode command array, goose uri`() {
        val g = guide(remote, sse, npm)
        assertEquals("[mcp_servers.dotsy]\nurl = \"https://mcp.dotsy.ai/mcp\"", snippet(g, "codex").text)
        assertFalse(snippet(g, "codex", "o2").supported)
        assertEquals("[mcp_servers.dotsy]\ncommand = \"npx\"\nargs = [\"-y\", \"@kaiva/postgres-mcp@0.1.1\", \"<database_url>\"]\n\n" +
            "[mcp_servers.dotsy.env]\nDATABASE_URL = \"<DATABASE_URL>\"\nLOG_LEVEL = \"info\"", snippet(g, "codex", "o3").text)
        assertTrue("\"httpUrl\": \"https://mcp.dotsy.ai/mcp\"" in snippet(g, "gemini-cli").text!!)
        assertTrue("\"url\": \"https://example.com/sse\"" in snippet(g, "gemini-cli", "o2").text!!)
        assertTrue("\"command\": [\"npx\", \"-y\", \"@kaiva/postgres-mcp@0.1.1\", \"<database_url>\"]" in snippet(g, "opencode", "o3").text!!)
        assertTrue("uri: \"https://mcp.dotsy.ai/mcp\"" in snippet(g, "goose").text!!)
        assertEquals("Goose does not support SSE servers.", snippet(g, "goose", "o2").note)
    }

    @Test
    fun `continue block file is valid yaml list`() {
        assertEquals("""
            name: "dotsy"
            version: "0.0.1"
            schema: "v1"
            mcpServers:
              - name: "dotsy"
                type: "streamable-http"
                url: "https://mcp.dotsy.ai/mcp"
        """.trimIndent(), snippet(guide(remote), "continue").text)
    }

    @Test
    fun `jetbrains says why when env is needed`() {
        val s = snippet(guide(npm), "jetbrains")
        assertFalse(s.supported)
        assertEquals("The documentation does not say how to pass environment variables.", s.note)
    }

    @Test
    fun `key comes from the title`() {
        assertEquals("postgres-mcp-server", SetupGuides.key("Postgres MCP Server!", "io.x/pg"))
        assertEquals("pg", SetupGuides.key("☃", "io.x/pg"))
    }
}
