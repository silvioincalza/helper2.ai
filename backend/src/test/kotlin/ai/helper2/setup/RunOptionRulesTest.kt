package ai.helper2.setup

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunOptionRulesTest {

    private fun target(line: String) = line.split(' ').let { RunOptionRules.target(it.first(), it.drop(1)) }

    @Test
    fun `package runners keep the package they run`() {
        assertEquals("@acme/pg-mcp@1.2.0", target("npx -y @acme/pg-mcp@1.2.0 postgres://localhost/db --read-only"))
        assertEquals("acme-mcp", target("uvx acme-mcp"))
        assertEquals("acme-mcp[pg]==2.0", target("uvx --from acme-mcp[pg]==2.0 acme-mcp[pg]==2.0"))
        assertEquals("acme-mcp", target("uvx --python 3.12 acme-mcp --port 1"))
        assertEquals("acme-mcp", target("bunx --bun acme-mcp"))
        assertEquals("Acme.Mcp@1.0.0", target("dnx Acme.Mcp@1.0.0 --yes"))
        assertEquals("ghcr.io/acme/pg-mcp:1.2", target("docker run -i --rm -e API_KEY -e LOG=info --init ghcr.io/acme/pg-mcp:1.2"))
        assertEquals("mcp/fetch", target("docker run -i --rm -v acme-data:/data --network bridge mcp/fetch"))
    }

    @Test
    fun `interpreters, shells and paths are never a launcher`() {
        listOf("node server.js", "python -m acme", "python3 -c print(1)", "deno run -A https://x.example/m.ts", "bun x.ts",
            "uv run acme", "podman run -i acme", "go run x", "java -jar x.jar", "bash -c x", "/usr/local/bin/npx acme")
            .forEach { assertNull(target(it), it) }
    }

    @Test
    fun `eval, inline code and unknown launcher flags are rejected`() {
        listOf("npx -c rm", "npx --call rm", "npx -p acme acme", "npx --package=acme acme", "npx -y -e x acme",
            "uvx --with git+https://x.example/r.git acme", "uvx --from ./local acme", "uvx -c x acme", "bunx -e x acme",
            "dnx --add-source https://x.example acme")
            .forEach { assertNull(target(it), it) }
    }

    @Test
    fun `a url, a path or a leading dash is never the package`() {
        listOf("npx -y https://x.example/pkg.tgz", "npx -y github:acme/x", "npx -y ./local", "npx -y -acme",
            "uvx git+https://x.example/r.git", "uvx /tmp/acme", "dnx -acme", "npx -y", "docker run -i --rm")
            .forEach { assertNull(target(it), it) }
    }

    @Test
    fun `docker runs one image and nothing that reaches the host`() {
        listOf(
            "docker run -i --rm --privileged acme/x",
            "docker run -i --rm -v /:/host acme/x",
            "docker run -i --rm -v ./data:/data acme/x",
            "docker run -i --rm -v ~/x:/x acme/x",
            "docker run -i --rm --volume=/etc:/etc acme/x",
            "docker run -i --rm --mount type=bind,src=/,dst=/h acme/x",
            "docker run -i --rm --network host acme/x",
            "docker run -i --rm --network=host acme/x",
            "docker run -i --rm --net container:other acme/x",
            "docker run -i --rm --pid host acme/x",
            "docker run -i --rm --cap-add SYS_ADMIN acme/x",
            "docker run -i --rm --env-file .env acme/x",
            "docker run -i --rm -p 8080:8080 acme/x",
            "docker run -i --rm acme/x sh -c id",
            "docker run -i --rm acme/x stdio",
            "docker exec -i box acme",
            "docker -H tcp://x.example run acme/x",
        ).forEach { assertNull(target(it), it) }
    }

    @Test
    fun `control characters in any argument reject the command`() {
        assertNull(RunOptionRules.target("npx", listOf("-y", "acme", "a\nb")))
        assertNull(RunOptionRules.target("npx", listOf("-y", "acme", "a\u007fb")))
    }

    @Test
    fun `identifiers and versions follow each registry's grammar`() {
        assertTrue(RunOptionRules.validIdentifier("npm", "@acme/pg-mcp"))
        assertTrue(RunOptionRules.validIdentifier("pypi", "acme_mcp.pg"))
        assertTrue(RunOptionRules.validIdentifier("nuget", "Acme.Mcp"))
        assertTrue(RunOptionRules.validIdentifier("oci", "ghcr.io/acme/pg-mcp:1.2"))
        assertTrue(RunOptionRules.validIdentifier("oci", "localhost:5000/acme/x@sha256:" + "a".repeat(64)))
        listOf("npm" to "-x", "npm" to "Acme", "npm" to "@acme/../x", "npm" to "https://x.example/p.tgz",
            "pypi" to "-x", "pypi" to "acme mcp", "pypi" to "git+https://x.example/r", "nuget" to "-x", "nuget" to "a/b",
            "oci" to "-x", "oci" to "Acme/X", "oci" to "acme/x --privileged", "cargo" to "acme")
            .forEach { (type, id) -> assertFalse(RunOptionRules.validIdentifier(type, id), "$type $id") }
        assertTrue(RunOptionRules.validVersion("1.2.0-rc.1+build.5"))
        listOf("-1", "1 2", "1;rm", "", "1\n").forEach { assertFalse(RunOptionRules.validVersion(it), it) }
    }

    @Test
    fun `env names and header names follow their grammar, values have no control characters`() {
        fun env(name: String, value: String? = null) =
            RunOption.Command("npx", listOf("-y", "acme"), listOf(Setting(name, SettingKind.ENV, null, true, false, value)))
        fun header(name: String) = RunOption.Remote(Transport.STREAMABLE_HTTP, URI("https://x.example/mcp"),
            listOf(Setting(name, SettingKind.HEADER, null, true, false)))
        assertTrue(RunOptionRules.accepts(env("_API_KEY2")))
        listOf("2KEY", "API-KEY", "A B", "A=B", "\$(id)", "").forEach { assertFalse(RunOptionRules.accepts(env(it)), it) }
        assertFalse(RunOptionRules.accepts(env("LOG", "info\nrm -rf /")))
        assertTrue(RunOptionRules.accepts(header("X-Api-Key")))
        listOf("X Api", "X:Api", "X\r\nY", "").forEach { assertFalse(RunOptionRules.accepts(header(it)), it) }
    }
}
