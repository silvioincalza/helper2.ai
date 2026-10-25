package ai.helper2.scout

import ai.helper2.shared.Verification
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceValidatorTest {

    private val evA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val material = listOf(
        Material(evA, URI("https://github.com/a/pg-mcp"), Instant.parse("2026-10-22T10:00:00Z"),
            "Postgres MCP server. Works with Claude Desktop and Cursor.  Licensed under the MIT license."),
    )
    private val input = EnrichmentInput(
        resourceTitle = "pg-mcp",
        materials = material,
        sourceFacts = mapOf("license" to "MIT"),
        knownResources = mapOf("claude desktop" to UUID.fromString("00000000-0000-0000-0000-0000000000cd")),
    )
    private val validator = EvidenceValidator()

    private fun field(name: String, value: String, excerpt: String = "Works with Claude Desktop", ev: UUID = evA) =
        ProposedField(name, value, listOf(Citation(ev.toString(), excerpt)))

    @Test
    fun `supported field with a real excerpt is verified`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(field("platforms", "desktop", "Works with Claude Desktop and Cursor"))))
        assertEquals(Verification.VERIFIED, r.fields.single().verification)
        assertEquals(EnrichmentOutcome.VALIDATED, r.outcome)
    }

    @Test
    fun `excerpt not present in retrieved material is rejected as hallucinated`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(field("pricing", "FREE", "Completely free forever"))))
        assertEquals(Verification.REJECTED, r.fields.single().verification)
        assertTrue(r.errors.any { "excerpt not found" in it })
        assertEquals(EnrichmentOutcome.REJECTED, r.outcome)
    }

    @Test
    fun `citation to an evidence id that was never retrieved is rejected`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(field("platforms", "desktop", ev = UUID.randomUUID()))))
        assertEquals(Verification.REJECTED, r.fields.single().verification)
        assertTrue(r.errors.any { "unknown evidence" in it })
    }

    @Test
    fun `field without any citation is rejected`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(ProposedField("techLevel", "ADVANCED", emptyList()))))
        assertEquals(Verification.REJECTED, r.fields.single().verification)
    }

    @Test
    fun `fields outside the schema or with disallowed values are rejected`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(
            field("stars", "99999"),
            field("pricing", "CHEAP"),
        )))
        assertTrue(r.fields.all { it.verification == Verification.REJECTED })
        assertTrue(r.errors.any { "field not allowed" in it })
        assertTrue(r.errors.any { "value not allowed" in it })
    }

    @Test
    fun `dates are never accepted from the model`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(field("published", "2024-01-01"))))
        assertEquals(Verification.REJECTED, r.fields.single().verification)
    }

    @Test
    fun `disagreement with a source provided fact becomes conflicting not overwritten`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(field("license", "Apache-2.0", "Licensed under the MIT license"))))
        assertEquals(Verification.CONFLICTING, r.fields.single().verification)
        assertTrue(r.needsReview)
    }

    @Test
    fun `relationship to a known resource named in the excerpt is verified`() {
        val r = validator.validate(input, EnrichmentProposal(relationships = listOf(
            ProposedRelationship("COMPATIBLE_WITH", "Claude Desktop", listOf(Citation(evA.toString(), "Works with Claude Desktop"))),
        )))
        val rel = r.relationships.single()
        assertEquals(Verification.VERIFIED, rel.verification)
        assertEquals(UUID.fromString("00000000-0000-0000-0000-0000000000cd"), rel.targetId)
    }

    @Test
    fun `relationship to an unknown target stays an unverified candidate for review`() {
        val r = validator.validate(input, EnrichmentProposal(relationships = listOf(
            ProposedRelationship("INTEGRATES_WITH", "Cursor", listOf(Citation(evA.toString(), "Claude Desktop and Cursor"))),
        )))
        assertEquals(Verification.UNVERIFIED, r.relationships.single().verification)
        assertTrue(r.needsReview)
    }

    @Test
    fun `relationship whose excerpt does not mention the target is rejected`() {
        val r = validator.validate(input, EnrichmentProposal(relationships = listOf(
            ProposedRelationship("USES", "LangChain", listOf(Citation(evA.toString(), "Works with Claude Desktop"))),
        )))
        assertEquals(Verification.REJECTED, r.relationships.single().verification)
    }

    @Test
    fun `summary keeps only claims with valid evidence`() {
        val r = validator.validate(input, EnrichmentProposal(summary = listOf(
            ProposedClaim("An MCP server for Postgres.", listOf(Citation(evA.toString(), "Postgres MCP server. Works"))),
            ProposedClaim("Used by Fortune 500 companies.", listOf(Citation(evA.toString(), "Fortune 500"))),
        )))
        assertEquals(listOf("An MCP server for Postgres."), r.summary.map { it.text })
        assertEquals(EnrichmentOutcome.PARTIAL, r.outcome)
    }

    @Test
    fun `summary keeps at most three claims, as the prompt asks`() {
        val cite = listOf(Citation(evA.toString(), "Postgres MCP server. Works"))
        val r = validator.validate(input, EnrichmentProposal(summary = (1..5).map { ProposedClaim("Claim $it.", cite) }))
        assertEquals(EvidenceValidator.MAX_SUMMARY_CLAIMS, 3)
        assertEquals(listOf("Claim 1.", "Claim 2.", "Claim 3."), r.summary.map { it.text })
    }

    @Test
    fun `excerpt matching ignores whitespace and case but not content`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(field("platforms", "desktop", "works   with claude DESKTOP"))))
        assertEquals(Verification.VERIFIED, r.fields.single().verification)
    }

    @Test
    fun `excessively short excerpts cannot support a claim`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(field("platforms", "desktop", "MCP"))))
        assertEquals(Verification.REJECTED, r.fields.single().verification)
    }

    @Test
    fun `an excerpt under twenty characters cannot support a claim`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(field("platforms", "desktop", "with Claude Desktop"))))
        assertEquals(Verification.REJECTED, r.fields.single().verification)
    }

    @Test
    fun `a platform the cited excerpt does not state stays unverified and goes to review`() {
        val r = validator.validate(input, EnrichmentProposal(fields = listOf(field("platforms", "cli", "Works with Claude Desktop and Cursor"))))
        assertEquals(Verification.UNVERIFIED, r.fields.single().verification)
        assertTrue(r.errors.any { "does not state the value" in it })
        assertTrue(r.needsReview)
    }

    @Test
    fun `a licence and open source are verified only when the excerpt names them`() {
        val noFacts = input.copy(sourceFacts = emptyMap())
        val stated = validator.validate(noFacts, EnrichmentProposal(fields = listOf(
            field("license", "MIT", "Licensed under the MIT license"),
            field("openSource", "true", "Licensed under the MIT license"),
        )))
        val notStated = validator.validate(noFacts, EnrichmentProposal(fields = listOf(
            field("license", "Apache-2.0", "Works with Claude Desktop and Cursor"),
            field("openSource", "true", "Works with Claude Desktop and Cursor"),
        )))

        assertEquals(listOf(Verification.VERIFIED, Verification.VERIFIED), stated.fields.map { it.verification })
        assertEquals(listOf(Verification.UNVERIFIED, Verification.UNVERIFIED), notStated.fields.map { it.verification })
    }

    @Test
    fun `a licence is stated by its family name and synonyms count as whole words`() {
        assertTrue("apache" in EvidenceValidator.statingWords("license", "Apache-2.0"))
        assertTrue("general public license" in EvidenceValidator.statingWords("license", "GPL-3.0-only"))
        val rapid = input.copy(materials = listOf(Material(evA, material[0].sourceUrl, material[0].retrievedAt, "A rapid prototyping tool for agents")))
        val r = validator.validate(rapid, EnrichmentProposal(fields = listOf(field("platforms", "api", "A rapid prototyping tool"))))
        assertEquals(Verification.UNVERIFIED, r.fields.single().verification)
    }

    @Test
    fun `an excerpt quoted with the angle brackets the model saw still matches`() {
        val tagged = input.copy(materials = listOf(Material(evA, material[0].sourceUrl, material[0].retrievedAt, "Run <cli> commands from any terminal")))
        val r = validator.validate(tagged, EnrichmentProposal(fields = listOf(field("platforms", "cli", "Run ‹cli› commands from any terminal"))))
        assertEquals(Verification.VERIFIED, r.fields.single().verification)
    }

    @Test
    fun `empty proposal is rejected not silently validated`() {
        assertEquals(EnrichmentOutcome.REJECTED, validator.validate(input, EnrichmentProposal()).outcome)
    }
}
