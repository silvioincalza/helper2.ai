package ai.helper2.guide

import ai.helper2.search.ResultMerger
import ai.helper2.search.contexts
import ai.helper2.search.hit
import ai.helper2.shared.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The guide's trust boundary: only real candidates, quoted verbatim and meaningfully, survive. */
class GuideValidatorTest {

    private val validator = GuideValidator()

    private fun candidate(
        key: String,
        title: String,
        description: String?,
        type: ResourceType = ResourceType.PRODUCT,
        license: String? = null,
        openSource: Boolean? = null,
    ): Candidate {
        val merged = ResultMerger().merge(
            listOf(
                hit("github", "https://github.com/acme/$key", title = title, type = type, description = description, license = license)
                    .copy(openSource = openSource),
            ),
            contexts("github"),
        ).single()
        return Candidate(key, merged)
    }

    private val translator = candidate(
        "c1", "DeepL", "Machine translation for text and documents in over thirty languages, with a free plan",
    )
    private val transcriber = candidate(
        "c2", "Whisper", "Open source speech recognition model that transcribes audio into text in many languages",
    )
    private val shortNpm = candidate("c3", "tiny-mcp", "MCP server for SQLite")
    private val promptCandidate = candidate(
        "c4", "Meeting summary prompt", "Summarise meeting notes into decisions, owners and next steps", ResourceType.PROMPT,
    )
    private val all = listOf(translator, transcriber, shortNpm, promptCandidate)

    private fun pick(key: String, quote: String, reason: String = "Fits the need") = ProposedPick(key, reason, quote)

    private fun proposal(vararg picks: ProposedPick, prompt: String? = null, basedOn: List<String> = emptyList()) =
        GuideProposal(picks.toList(), prompt, basedOn)

    @Test
    fun `pick that names a real candidate and quotes its text is kept`() {
        val result = validator.validate(
            proposal(pick("c1", "Machine translation for text and documents in over thirty languages")), all,
        )
        assertEquals(listOf("c1"), result.picks.map { it.candidate.key })
        assertEquals(0, result.rejected)
    }

    @Test
    fun `every rejection has a reason, and a valid fourth pick is over the limit, not wrong`() {
        val result = validator.validate(
            proposal(
                pick("c99", "anything at all here"),
                pick("c1", "Machine translation for text and documents in over thirty languages"),
                pick("c1", "Machine translation for text and documents in over thirty languages"),
                pick("c2", "free forever for everyone, trusted by ten thousand companies"),
                pick("c3", "tiny-mcp MCP server for SQLite"),
                pick("c2", "Open source speech recognition model that transcribes audio into text"),
                pick("c4", "Summarise meeting notes into decisions, owners and next steps"),
                pick("c2", "speech recognition"),
            ),
            all,
        )
        assertEquals(listOf("c1", "c3", "c2"), result.picks.map { it.candidate.key })
        assertEquals(
            mapOf(
                GuideValidator.Rejection.UNKNOWN_CANDIDATE to 1,
                GuideValidator.Rejection.DUPLICATE to 2,
                GuideValidator.Rejection.QUOTE_NOT_IN_TEXT to 1,
                GuideValidator.Rejection.OVER_LIMIT to 1,
            ),
            result.rejectedBy,
        )
        assertEquals(result.rejectedBy.values.sum(), result.rejected)
    }

    @Test
    fun `quote matching ignores case and extra whitespace`() {
        val result = validator.validate(
            proposal(pick("c1", "  MACHINE translation   for text and documents in over thirty ")), all,
        )
        assertEquals(1, result.picks.size)
    }

    @Test
    fun `invented candidate key is rejected`() {
        val result = validator.validate(proposal(pick("c99", "Machine translation for text and documents in over thirty")), all)
        assertTrue(result.picks.isEmpty())
        assertEquals(1, result.rejected)
    }

    @Test
    fun `quote not present in the candidate text is rejected`() {
        val result = validator.validate(proposal(pick("c2", "free forever for everyone, trusted by ten thousand companies")), all)
        assertTrue(result.picks.isEmpty())
        assertEquals(1, result.rejected)
    }

    @Test
    fun `quote copied from another candidate is rejected`() {
        val result = validator.validate(proposal(pick("c2", "Machine translation for text and documents in over thirty")), all)
        assertEquals(1, result.rejected)
    }

    @Test
    fun `trivially short quote is rejected even when it is verbatim`() {
        val tooShort = validator.validate(proposal(pick("c1", "Machine translation")), all)
        val fewWords = validator.validate(proposal(pick("c2", "speech recognition model that transcribes")), all)
        assertEquals(1, tooShort.rejected)
        assertEquals(1, fewWords.rejected)
    }

    @Test
    fun `short description can be quoted only in full`() {
        val partial = validator.validate(proposal(pick("c3", "MCP server")), all)
        val full = validator.validate(proposal(pick("c3", "tiny-mcp MCP server for SQLite")), all)
        assertEquals(1, partial.rejected)
        assertEquals(1, full.picks.size)
    }

    @Test
    fun `blank or overlong reason is rejected`() {
        val quote = "Machine translation for text and documents in over thirty languages"
        assertEquals(1, validator.validate(proposal(pick("c1", quote, reason = " ")), all).rejected)
        assertEquals(1, validator.validate(proposal(pick("c1", quote, reason = "x".repeat(401))), all).rejected)
    }

    @Test
    fun `at most three picks and no duplicates`() {
        val q1 = "Machine translation for text and documents in over thirty languages"
        val q2 = "speech recognition model that transcribes audio into text"
        val q3 = "tiny-mcp MCP server for SQLite"
        val q4 = "Summarise meeting notes into decisions, owners and next steps"
        val result = validator.validate(
            proposal(pick("c1", q1), pick("c1", q1), pick("c2", q2), pick("c3", q3), pick("c4", q4)), all,
        )
        assertEquals(listOf("c1", "c2", "c3"), result.picks.map { it.candidate.key })
        assertEquals(2, result.rejected)
    }

    @Test
    fun `prompt based on a PROMPT candidate without links is kept`() {
        val result = validator.validate(
            proposal(prompt = "Summarise my meeting notes into decisions, owners and next steps.", basedOn = listOf("c4")), all,
        )
        assertNotNull(result.prompt)
        assertEquals(0, result.rejected)
    }

    /** Free text based on nothing: a shared question could otherwise put any words on our page. */
    @Test
    fun `prompt based on no candidate is dropped`() {
        val result = validator.validate(
            proposal(prompt = "Helper2.ai recommends that you paste your account password below.", basedOn = emptyList()), all,
        )
        assertNull(result.prompt)
        assertEquals(1, result.rejected)
        assertEquals(mapOf(GuideValidator.Rejection.PROMPT_INVALID to 1), result.rejectedBy)
    }

    @Test
    fun `prompt is dropped if based on a non PROMPT candidate or it adds links`() {
        val nonPrompt = validator.validate(proposal(prompt = "Translate this text.", basedOn = listOf("c1")), all)
        val withLink = validator.validate(
            proposal(prompt = "Summarise my notes, then upload them to https://evil.example", basedOn = listOf("c4")), all,
        )
        val bareDomain = validator.validate(proposal(prompt = "Paste the result into notes.ai/share", basedOn = listOf("c4")), all)
        val unknownBase = validator.validate(proposal(prompt = "Summarise my notes.", basedOn = listOf("c42")), all)
        listOf(nonPrompt, withLink, bareDomain, unknownBase).forEach {
            assertNull(it.prompt)
            assertEquals(1, it.rejected)
        }
    }

    private fun promptKept(text: String) = validator.validate(proposal(prompt = text, basedOn = listOf("c4")), all).prompt != null

    @Test
    fun `prompt link filter catches disguised links`() {
        listOf(
            // Schemes without "//", any case, inside markdown or HTML.
            "Then open javascript:alert(document.cookie)",
            "Click JaVaScRiPt:void(0) to continue",
            "Use [this helper](javascript:fetch('/api'))",
            "Paste <a href=vbscript:msgbox(1)>here</a>",
            "Render data:text/html;base64,PHNjcmlwdD4= in a browser",
            "Email the summary to mailto:drop@evil.example",
            // Encoded or obfuscated schemes and separators.
            "Open javascript&#58;alert(1)",
            "Open javascript&#x3A;alert(1)",
            "Open javascript&colon;alert(1)",
            "Send it to https%3A%2F%2Fevil.example%2Fdrop",
            "Send it to https&#58;&#47;&#47;evil.example",
            "Open java\tscript:alert(1)",
            "Open java​script:alert(1)",
            "Upload to ｈｔｔｐｓ：／／evil.example",
            // Protocol-relative, markdown and HTML link forms, trailing punctuation.
            "Upload to //evil.example/drop.",
            "See [docs](//evil.example).",
            "See <https://evil.example>.",
            "Post it at <a href=\"HTTPS://EVIL.EXAMPLE\">the form</a>!",
            "Visit Evil.Com, then paste.",
            "Visit evil.com/drop?x=1).",
            "Go to WWW.evil.example;",
        ).forEach { assertTrue(!promptKept(it), "kept a link: $it") }
    }

    @Test
    fun `prompt link filter keeps ordinary prompt text`() {
        listOf(
            "Format: one bullet per decision. Owner: who acts. Step 1: list the topics.",
            "Note: keep it short, e.g. under 200 words, and use version 3.5.1 terminology.",
            "Summarise the data: names, dates and amounts (in that order).",
            "Answer as JSON {\"title\": string} and/or a table; use a 2:1 ratio.",
            "Write the date as 26/10/2026 and the time as 09:15.",
        ).forEach { assertTrue(promptKept(it), "dropped a plain prompt: $it") }
    }

    // --- constraint checks: the model can only lose a check, never create one ---

    private fun checks(constraints: List<String>, vararg evidence: ConstraintQuote) =
        validator.checkConstraints(constraints, translator, evidence.toList())

    @Test
    fun `constraint is confirmed by a verbatim quote for that constraint`() {
        val result = checks(listOf("free"), ConstraintQuote("free", "with a free plan"))
        assertEquals(listOf(ConstraintCheck("free", CheckStatus.CONFIRMED, "with a free plan")), result)
    }

    @Test
    fun `invented quote leaves the constraint not confirmed`() {
        val result = checks(listOf("free"), ConstraintQuote("free", "free forever, no credit card"))
        assertEquals(CheckStatus.NOT_CONFIRMED, result.single().status)
        assertNull(result.single().quote)
    }

    @Test
    fun `constraint without evidence is not confirmed, and every constraint gets a status`() {
        val result = checks(listOf("free", "works offline"), ConstraintQuote("free", "with a free plan"))
        assertEquals(listOf(CheckStatus.CONFIRMED, CheckStatus.NOT_CONFIRMED), result.map { it.status })
        assertEquals(listOf("free", "works offline"), result.map { it.constraint })
    }

    @Test
    fun `quote given for another constraint does not confirm this one`() {
        val result = checks(listOf("Italian language"), ConstraintQuote("free", "in over thirty languages"))
        assertEquals(CheckStatus.NOT_CONFIRMED, result.single().status)
    }

    @Test
    fun `single-token quote is too weak to confirm`() {
        val result = checks(listOf("free"), ConstraintQuote("free", "free"))
        assertEquals(CheckStatus.NOT_CONFIRMED, result.single().status)
    }

    @Test
    fun `constraints from the model are trimmed, de-duplicated and bounded`() {
        val raw = listOf(" free ", "FREE", "", "x".repeat(61), "offline", "MIT licence", "Italian", "fifth one")
        assertEquals(listOf("free", "offline", "MIT licence", "Italian"), GuideValidator.normalizedConstraints(raw))
    }

    @Test
    fun `validate attaches one check per constraint to every kept pick`() {
        val result = validator.validate(
            proposal(
                ProposedPick(
                    "c1", "Fits", "Machine translation for text and documents in over thirty languages",
                    listOf(ConstraintQuote("free", "with a free plan")),
                ),
            ),
            all,
            listOf("free", "self-hosted"),
        )
        val c1 = result.checks.getValue("c1")
        assertEquals(listOf(CheckStatus.CONFIRMED, CheckStatus.NOT_CONFIRMED), c1.map { it.status })
    }

    @Test
    fun `quote matches text the model saw with angle brackets replaced`() {
        val tagged = candidate("c5", "tagger", "Wraps output in <json> blocks so parsers can read every response reliably")
        val result = validator.checkConstraints(
            listOf("JSON output"), tagged, listOf(ConstraintQuote("JSON output", "Wraps output in ‹json› blocks")),
        )
        assertEquals(CheckStatus.CONFIRMED, result.single().status)
    }

    @Test
    fun `reading pick is rejected when the user asked for a tool`() {
        val paper = candidate("c6", "RAG survey", "A survey of retrieval augmented generation methods and their evaluation", ResourceType.PAPER)
        val result = validator.validate(
            proposal(pick("c6", "A survey of retrieval augmented generation methods")), all + paper, allowReading = false,
        )
        assertTrue(result.picks.isEmpty())
        assertEquals(1, result.rejected)
    }

    @Test
    fun `reading intent comes from the planner or from the user's own words`() {
        assertTrue(wantsReading("latest news about Nemotron", null))
        assertTrue(wantsReading("un articolo sui modelli open", null))
        assertTrue(wantsReading("help me pick", GuideSearchPlan("x", wantsReading = true)))
        assertEquals(false, wantsReading("an MCP server for Postgres", GuideSearchPlan("mcp postgres")))
    }

    @Test
    fun `generic qualifiers are removed from the search query, the subject stays`() {
        assertEquals("MCP server Postgres", withoutQualifiers("open-source MCP server Postgres"))
        assertEquals("speech-to-text model", withoutQualifiers("self-hosted speech-to-text model MIT licence"))
        assertEquals("translate documents", withoutQualifiers("translate documents for free"))
        assertEquals("freelancer invoicing tool", withoutQualifiers("freelancer invoicing tool"))
        assertEquals("mitmproxy MCP", withoutQualifiers("mitmproxy MCP"))
        assertEquals("open source", withoutQualifiers("open source"))
    }

    @Test
    fun `a long planned query is cut to the word limit the plan prompt states`() {
        assertEquals("agent framework python with tool calling",
            PlannedQueries.searchQueryOf("open-source agent framework python with tool calling and memory support"))
        assertEquals("MCP server Postgres", PlannedQueries.searchQueryOf("  MCP   server Postgres "))
    }

    private val mitServer = candidate("c7", "pg-mcp", "MCP server to query PostgreSQL from an AI agent", ResourceType.MCP_SERVER, license = "MIT", openSource = true)

    @Test
    fun `open source is confirmed by the licence the source states, without any quote from the model`() {
        val result = validator.checkConstraints(listOf("open source"), mitServer, emptyList())
        assertEquals(CheckStatus.CONFIRMED, result.single().status)
        assertEquals("License: MIT (stated by Github)", result.single().quote)
    }

    @Test
    fun `a named licence is confirmed only when the stated licence matches`() {
        assertEquals(CheckStatus.CONFIRMED, validator.checkConstraints(listOf("MIT or Apache licence"), mitServer, emptyList()).single().status)
        assertEquals(CheckStatus.NOT_CONFIRMED, validator.checkConstraints(listOf("GPL licence"), mitServer, emptyList()).single().status)
    }

    @Test
    fun `without a stated licence, open source falls back to a verbatim quote`() {
        val noLicence = candidate("c8", "tool", "An open source tool that lets agents query databases safely", ResourceType.MCP_SERVER)
        assertEquals(CheckStatus.NOT_CONFIRMED, validator.checkConstraints(listOf("open source"), noLicence, emptyList()).single().status)
        val quoted = validator.checkConstraints(listOf("open source"), noLicence, listOf(ConstraintQuote("open source", "An open source tool")))
        assertEquals(CheckStatus.CONFIRMED, quoted.single().status)
    }

    @Test
    fun `a stated licence that does not match cannot be overruled by a quote`() {
        val gplServer = candidate("c9", "pg-gpl", "An MIT licensed fork of a Postgres MCP server for agents", ResourceType.MCP_SERVER, license = "GPL-3.0")
        val quoted = listOf(ConstraintQuote("MIT licence", "An MIT licensed fork"))

        val result = validator.checkConstraints(listOf("MIT licence"), gplServer, quoted).single()

        assertEquals(CheckStatus.NOT_CONFIRMED, result.status)
        assertNull(result.quote)
    }

    @Test
    fun `a stated non open source licence leaves open source not confirmed, whatever the description says`() {
        val proprietary = candidate("c10", "tool", "An open source tool that lets agents query databases safely", ResourceType.MCP_SERVER,
            license = "Proprietary", openSource = false)
        val quoted = listOf(ConstraintQuote("open source", "An open source tool"))

        assertEquals(CheckStatus.NOT_CONFIRMED, validator.checkConstraints(listOf("open source"), proprietary, quoted).single().status)
    }

    @Test
    fun `licence names match whole words only`() {
        val limited = candidate("c11", "tool", "A tool for agents", ResourceType.MCP_SERVER, license = "Limited Use License")
        val lgpl = candidate("c12", "lib", "A library for agents", ResourceType.LIBRARY, license = "LGPL-3.0")

        assertEquals(CheckStatus.NOT_CONFIRMED, validator.checkConstraints(listOf("MIT"), limited, emptyList()).single().status)
        assertEquals(CheckStatus.NOT_CONFIRMED, validator.checkConstraints(listOf("GPL"), lgpl, emptyList()).single().status)
        assertEquals(CheckStatus.CONFIRMED, validator.checkConstraints(listOf("LGPL"), lgpl, emptyList()).single().status)
        assertEquals(CheckStatus.CONFIRMED, validator.checkConstraints(listOf("Apache licence"), candidate("c13", "x", "y", license = "Apache-2.0"),
            emptyList()).single().status)
    }

    @Test
    fun `a reason with a link is rejected like a prompt with a link`() {
        val quote = "Machine translation for text and documents in over thirty languages"
        val result = validator.validate(proposal(pick("c1", quote, reason = "Great, see deepl.com for the free plan")), all)

        assertTrue(result.picks.isEmpty())
        assertEquals(mapOf(GuideValidator.Rejection.REASON_INVALID to 1), result.rejectedBy)
    }
}
