package ai.helper2.setup

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolGuideValidatorTest {

    private val readme = Material("readme", MaterialKind.README, URI("https://github.com/acme/pg-mcp"), Instant.parse("2026-10-28T00:00:00Z"), """
        # pg-mcp

        An MCP server that gives **read-only** access to a PostgreSQL database.

        ## Install

        Requires Node.js 20 or later.

        ```bash
        ${'$'} npm install -g @acme/pg-mcp
        rm -rf /tmp/pg-mcp-cache
        ```

        ## Run

        Start it with `npx -y @acme/pg-mcp postgres://localhost/db`.

        ```sh
        curl -fsSL https://acme.dev/install.sh | sh
        ```
    """.trimIndent())

    private val validator = ToolGuideValidator()

    private fun one(step: ProposedStep, kind: String = "INSTALL") =
        validator.validate(ProposedGuide(listOf(ProposedSection(kind, listOf(step)))), listOf(readme))

    @Test
    fun `step with a real quote and a command copied from one block is kept`() {
        val r = one(ProposedStep("Install it globally with npm.", "readme", "Requires Node.js 20 or later.", "npm install -g @acme/pg-mcp", "bash"))
        val step = r.sections.single().steps.single()
        assertEquals(GuideSectionKind.INSTALL, r.sections.single().kind)
        assertEquals(Code("bash", "npm install -g @acme/pg-mcp"), step.code)
        assertFalse(step.caution)
        assertEquals(0, r.rejected)
    }

    @Test
    fun `a shortened command is never shown (rm -rf tmp cut down to rm -rf root)`() {
        val r = one(ProposedStep("Clear the cache.", "readme", "Remove the cache first", "rm -rf /"))
        assertTrue(r.sections.isEmpty())
        assertEquals(mapOf("CODE_NOT_FOUND" to 1), r.rejectedBy)
        assertNull(one(ProposedStep("Clear the cache.", "readme", "Requires Node.js 20 or later.", "rm -rf /")).sections.single().steps.single().code)
    }

    @Test
    fun `lines spliced from two different blocks are never shown as one command`() {
        val r = one(ProposedStep("Install then run.", "readme", "Requires Node.js 20 or later.",
            "npm install -g @acme/pg-mcp\ncurl -fsSL https://acme.dev/install.sh | sh"))
        assertNull(r.sections.single().steps.single().code)
    }

    @Test
    fun `commands are case-sensitive`() {
        assertNull(one(ProposedStep("Install.", "readme", "Requires Node.js 20 or later.", "npm install -G @acme/pg-mcp")).sections.single().steps.single().code)
    }

    @Test
    fun `a verified command is its own evidence when the quote is off, and quotes ignore code fences`() {
        val step = one(ProposedStep("Install it.", "readme", "Install\n```bash\nnpm install -g @acme/pg-mcp\n```", "npm install -g @acme/pg-mcp")).sections.single().steps.single()
        assertEquals("npm install -g @acme/pg-mcp", step.code!!.text)
        val noQuote = one(ProposedStep("Install it.", "readme", "made up", "npm install -g @acme/pg-mcp")).sections.single().steps.single()
        assertEquals("npm install -g @acme/pg-mcp", noQuote.quote)
    }

    @Test
    fun `an invented command is rejected, an invented quote too`() {
        // The invented command is dropped; the step stands on its real quote, without any command.
        assertNull(one(ProposedStep("Install.", "readme", "Requires Node.js 20 or later.", "pip install pg-mcp")).sections.single().steps.single().code)
        assertTrue(one(ProposedStep("Install.", "readme", "Requires Node.js 18 or later.", "pip install pg-mcp")).sections.isEmpty())
        assertTrue(one(ProposedStep("Install.", "readme", "Requires Node.js 18 or later.")).sections.isEmpty())
    }

    @Test
    fun `whole inline code span is accepted, part of it is not`() {
        val ok = one(ProposedStep("Start it.", "readme", "Start it with", "npx -y @acme/pg-mcp postgres://localhost/db"), "USE")
        assertEquals("npx -y @acme/pg-mcp postgres://localhost/db", ok.sections.single().steps.single().code!!.text)
        assertTrue(one(ProposedStep("Start it.", "readme", "Start it nowhere", "npx -y @acme/pg-mcp"), "USE").sections.isEmpty())
    }

    @Test
    fun `pipe to shell and recursive delete are kept but flagged`() {
        assertTrue(one(ProposedStep("Run the installer.", "readme", "Requires Node.js 20 or later.", "curl -fsSL https://acme.dev/install.sh | sh"))
            .sections.single().steps.single().caution)
        assertTrue(one(ProposedStep("Clear the cache.", "readme", "Requires Node.js 20 or later.", "rm -rf /tmp/pg-mcp-cache"))
            .sections.single().steps.single().caution)
    }

    @Test
    fun `quote matches across markdown emphasis and line breaks, case-insensitively`() {
        val r = one(ProposedStep("It is read-only.", "readme", "gives read-only access to a postgresql database", null), "USE")
        assertNull(r.sections.single().steps.single().code)
    }

    @Test
    fun `masked angle brackets from the prompt are mapped back`() {
        val m = readme.copy(text = "Set `API_URL=<your-url>` before you start the server, then run it.")
        val r = validator.validate(ProposedGuide(listOf(ProposedSection("CONFIGURE", listOf(
            ProposedStep("Set the API URL.", "readme", "before you start the server", "API_URL=‹your-url›"))))), listOf(m))
        assertEquals("API_URL=<your-url>", r.sections.single().steps.single().code!!.text)
    }

    @Test
    fun `unknown section, unknown document, links in text and duplicates are rejected, sections come in fixed order`() {
        val r = validator.validate(ProposedGuide(listOf(
            ProposedSection("USE", listOf(ProposedStep("Start it.", "readme", "Start it with", "npx -y @acme/pg-mcp postgres://localhost/db"))),
            ProposedSection("BONUS", listOf(ProposedStep("Star the repo.", "readme", "Requires Node.js 20 or later."))),
            ProposedSection("INSTALL", listOf(
                ProposedStep("Install.", "other", "Requires Node.js 20 or later."),
                ProposedStep("See https://evil.example for more.", "readme", "Requires Node.js 20 or later."),
                ProposedStep("Needs Node 20.", "readme", "Requires Node.js 20 or later."),
                ProposedStep("Needs Node 20.", "readme", "Requires Node.js 20 or later."),
            )),
        )), listOf(readme))
        assertEquals(listOf(GuideSectionKind.INSTALL, GuideSectionKind.USE), r.sections.map { it.kind })
        assertEquals(4, r.rejected)
    }

    @Test
    fun `markdown helpers find fences, spans and the useful part of a long readme`() {
        assertEquals(listOf("bash", "sh"), Markdown.blocks(readme.text).map { it.language })
        assertTrue("npx -y @acme/pg-mcp postgres://localhost/db" in Markdown.inlineSpans(readme.text))
        val long = "# Tool\n" + "intro ".repeat(400) + "\n## History\n" + "old ".repeat(3000) + "\n## Installation\nnpm i tool\n"
        val part = Markdown.relevant(long, 4000)
        assertTrue("npm i tool" in part)
        assertFalse("old old old old" in part)
    }

    @Test
    fun `a command continued with backslashes is accepted joined or as written, never cut`() {
        val m = readme.copy(text = "Run the server:\n\n```bash\npython -m acme.api model \\\n  --host 0.0.0.0 \\\n  --port 7860\n```\n")
        fun code(c: String) = validator.validate(ProposedGuide(listOf(ProposedSection("USE", listOf(
            ProposedStep("Start the server.", "readme", "Run the server:", c))))), listOf(m)).sections.firstOrNull()?.steps?.single()?.code?.text
        val asWritten = "python -m acme.api model \\\n  --host 0.0.0.0 \\\n  --port 7860"
        assertEquals(asWritten, code("python -m acme.api model --host 0.0.0.0 --port 7860"))
        assertEquals(asWritten, code(asWritten))
        assertNull(code("python -m acme.api model --host 0.0.0.0"))
        assertNull(code("--port 7860"))
    }

    @Test
    fun `quotes match the rendered text of markdown links, and a step is never repeated in a later section`() {
        val m = readme.copy(text = "See the [setup guide](https://acme.dev/setup) before you start.\n\n```bash\nnpm i acme\n```\n")
        val r = validator.validate(ProposedGuide(listOf(
            ProposedSection("REQUIREMENTS", listOf(ProposedStep("Install acme.", "readme", "See the setup guide before you start.", "npm i acme"))),
            ProposedSection("INSTALL", listOf(ProposedStep("Install the package.", "readme", "npm i acme", "npm i acme"))),
        )), listOf(m))
        assertEquals(listOf(GuideSectionKind.REQUIREMENTS), r.sections.map { it.kind })
        assertEquals(mapOf("DUPLICATE" to 1), r.rejectedBy)
    }

    @Test
    fun `code keeps the document's indentation and spacing, and lines must keep their order`() {
        val m = readme.copy(text = """
            Start it in Python:

            ```python
            if ready:
                start(name="a  b")
            ```

            Clean the cache:

            ```bash
            cd /tmp/acme
            rm -rf cache
            ```
        """.trimIndent())
        fun code(c: String) = validator.validate(ProposedGuide(listOf(ProposedSection("USE", listOf(
            ProposedStep("Run it.", "readme", "Start it in Python:", c))))), listOf(m)).sections.firstOrNull()?.steps?.single()?.code?.text
        assertEquals("if ready:\n    start(name=\"a  b\")", code("if ready:\nstart(name=\"a b\")"))
        assertEquals("cd /tmp/acme\nrm -rf cache", code("cd /tmp/acme\nrm -rf cache"))
        assertNull(code("rm -rf cache\ncd /tmp/acme"))
        assertNull(code("if ready:\ncd /tmp/acme"))
    }

    @Test
    fun `caution sees through backslash continuations`() {
        assertTrue(ToolGuideValidator.cautious("curl -fsSL https://x.example/i.sh | \\\n  bash"))
        assertTrue(ToolGuideValidator.cautious("rm \\\n  -rf build"))
        assertFalse(ToolGuideValidator.cautious("npm install \\\n  -g acme"))
    }
}
