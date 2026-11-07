package ai.helper2.scout

import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The validator rules of ADR 0023: stating words for pricing, type and use case; judgement fields
 * as AI-estimated; self-declared facts and relationships from a vendor's page; and summary
 * sentences that keep to their excerpts.
 */
class EvidenceValidatorLadderTest {

  private val now = Instant.parse("2026-11-05T10:00:00Z")
  private val record = UUID.fromString("00000000-0000-0000-0000-00000000000a")
  private val page = UUID.fromString("00000000-0000-0000-0000-00000000000b")
  private val recordText =
    "Acme Notes\nAcme Notes is a writing assistant for teams. Free plan available. Runs in the browser."
  private val pageText =
    "Pricing\nStarter: \$12 per month per seat. Start your 14-day free trial today.\n" +
      "Acme Notes is open source and you can self-host it with Docker.\n" +
      "Acme Notes integrates with OpenAI, Anthropic, Google, Slack and Notion."
  private val materials =
    listOf(
      Material(record, URI("https://acme.example/"), now, recordText),
      Material(
        page,
        URI("https://acme.example/pricing"),
        now,
        pageText,
        anchorRecordId = record,
        supportsClaims = true,
        selfDeclared = true,
      ),
    )
  private val validator = EvidenceValidator()

  private fun input(facts: Map<String, String> = emptyMap()) =
    EnrichmentInput("Acme Notes", materials, facts)

  private fun field(field: String, value: String, id: UUID, excerpt: String) =
    validator
      .validate(
        input(),
        EnrichmentProposal(
          fields = listOf(ProposedField(field, value, listOf(Citation(id.toString(), excerpt))))
        ),
      )
      .fields
      .single()

  @Test
  fun `a free plan states FREEMIUM`() {
    val f = field("pricing", "FREEMIUM", record, "teams. Free plan available.")
    assertEquals(Verification.VERIFIED, f.verification)
    assertEquals(ValueOrigin.AI_EXTRACTED, f.origin)
  }

  @Test
  fun `a free trial never states FREE or FREEMIUM`() {
    val excerpt = "Start your 14-day free trial today."
    assertEquals(Verification.UNVERIFIED, field("pricing", "FREE", page, excerpt).verification)
    assertEquals(Verification.UNVERIFIED, field("pricing", "FREEMIUM", page, excerpt).verification)
  }

  @Test
  fun `a price per month states PAID, and only the vendor page says it`() {
    val f = field("pricing", "PAID", page, "Starter: \$12 per month per seat.")
    assertEquals(Verification.VERIFIED, f.verification)
    assertEquals(ValueOrigin.SELF_DECLARED, f.origin)
  }

  @Test
  fun `self-hosting states OPEN_SOURCE_SELF_HOSTED`() {
    val f = field("pricing", "OPEN_SOURCE_SELF_HOSTED", page, "you can self-host it with Docker")
    assertEquals(Verification.VERIFIED, f.verification)
  }

  @Test
  fun `a type and a use case need words that state them`() {
    assertEquals(
      Verification.VERIFIED,
      field("useCase", "writing", record, "is a writing assistant for teams").verification,
    )
    assertEquals(
      Verification.UNVERIFIED,
      field("useCase", "coding", record, "is a writing assistant for teams").verification,
    )
    assertEquals(
      Verification.VERIFIED,
      field("type", "PRODUCT", record, "is a writing assistant for teams").verification,
    )
    assertEquals(
      Verification.UNVERIFIED,
      field("type", "MCP_SERVER", record, "is a writing assistant for teams").verification,
    )
  }

  @Test
  fun `audience and tech level may be estimated without a quote, never verified`() {
    val r =
      validator.validate(
        input(),
        EnrichmentProposal(
          fields =
            listOf(
              ProposedField("audience", "USE_AI", emptyList()),
              ProposedField("techLevel", "BEGINNER", emptyList()),
            )
        ),
      )
    assertTrue(r.fields.all { it.origin == ValueOrigin.AI_ESTIMATED })
    assertTrue(r.fields.all { it.verification == Verification.UNVERIFIED })
    assertTrue(r.errors.none { "audience" in it || "techLevel" in it })
    // An estimate proves nothing: a run of estimates only keeps nothing.
    assertEquals(0, r.accepted)
    assertEquals(EnrichmentOutcome.REJECTED, r.outcome)
  }

  @Test
  fun `a fact is never AI-estimated, even without a quote`() {
    val r =
      validator.validate(
        input(),
        EnrichmentProposal(fields = listOf(ProposedField("pricing", "FREE", emptyList()))),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
    assertFalse(r.fields.any { it.origin == ValueOrigin.AI_ESTIMATED })
  }

  @Test
  fun `the licence the source states wins over a vendor page`() {
    val r =
      validator.validate(
        input(mapOf("openSource" to "false")),
        EnrichmentProposal(
          fields =
            listOf(
              ProposedField(
                "openSource",
                "true",
                listOf(
                  Citation(page.toString(), "Acme Notes is open source and you can self-host")
                ),
              )
            )
        ),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
  }

  @Test
  fun `relationships a vendor page names stay candidates, at most three`() {
    val cite = listOf(Citation(page.toString(), "integrates with OpenAI, Anthropic, Google, Slack"))
    val r =
      validator.validate(
        input().copy(knownResources = mapOf("openai" to UUID.randomUUID())),
        EnrichmentProposal(
          relationships =
            listOf("OpenAI", "Anthropic", "Google", "Slack").map {
              ProposedRelationship("INTEGRATES_WITH", it, cite)
            }
        ),
      )
    assertTrue(r.relationships.none { it.verification == Verification.VERIFIED })
    assertEquals(
      3,
      r.relationships.count {
        it.origin == ValueOrigin.SELF_DECLARED && it.verification != Verification.REJECTED
      },
    )
    assertEquals(Verification.REJECTED, r.relationships.last().verification)
  }

  private fun claim(text: String, id: UUID, excerpt: String) =
    validator.validate(
      input(),
      EnrichmentProposal(
        summary = listOf(ProposedClaim(text, listOf(Citation(id.toString(), excerpt))))
      ),
    )

  @Test
  fun `a sentence that keeps to its excerpt passes, and is self-declared from a vendor page`() {
    val r =
      claim("Acme Notes can be self-hosted with Docker.", page, "you can self-host it with Docker")
    assertEquals(1, r.summary.size, r.errors.toString())
    assertTrue(r.summary.single().selfDeclared)
  }

  @Test
  fun `a name or a number not in the excerpt drops the sentence`() {
    assertTrue(
      claim("Acme Notes works with Microsoft Word.", record, "writing assistant for teams")
        .summary
        .isEmpty()
    )
    assertTrue(
      claim("Acme Notes serves 500 teams.", record, "writing assistant for teams").summary.isEmpty()
    )
  }

  @Test
  fun `superlatives, certificates, popularity and comparisons are dropped`() {
    for (text in
      listOf(
        "Acme Notes is the best writing assistant for teams.",
        "Acme Notes is a leading writing assistant.",
        "Acme Notes is trusted by teams.",
        "Acme Notes is SOC 2 compliant.",
        "Acme Notes is a writing assistant for teams versus others.",
      )) {
      assertTrue(claim(text, record, "writing assistant for teams").summary.isEmpty(), text)
    }
  }

  @Test
  fun `a sentence with too few of its words in the excerpt is dropped`() {
    val r =
      claim(
        "Acme Notes rewrites legal contracts and negotiates salaries automatically.",
        record,
        "writing assistant for teams",
      )
    assertTrue(r.summary.isEmpty())
  }

  /** Codex review of #217: a neutral record quote must not hide that only the vendor names it. */
  @Test
  fun `a relationship only the vendor page names stays self-declared next to a neutral record quote`() {
    val r =
      validator.validate(
        input().copy(knownResources = mapOf("slack" to UUID.randomUUID())),
        EnrichmentProposal(
          relationships =
            listOf(
              ProposedRelationship(
                "INTEGRATES_WITH",
                "Slack",
                listOf(
                  Citation(page.toString(), "integrates with OpenAI, Anthropic, Google, Slack"),
                  Citation(record.toString(), "is a writing assistant for teams"),
                ),
              )
            )
        ),
      )
    val rel = r.relationships.single()
    assertEquals(ValueOrigin.SELF_DECLARED, rel.origin)
    assertEquals(Verification.UNVERIFIED, rel.verification, "a candidate for review, not verified")
  }

  @Test
  fun `a fact only the vendor page states stays self-declared next to a neutral record quote`() {
    val f =
      validator
        .validate(
          input(),
          EnrichmentProposal(
            fields =
              listOf(
                ProposedField(
                  "pricing",
                  "PAID",
                  listOf(
                    Citation(page.toString(), "Starter: \$12 per month per seat."),
                    Citation(record.toString(), "is a writing assistant for teams"),
                  ),
                )
              )
          ),
        )
        .fields
        .single()
    assertEquals(Verification.VERIFIED, f.verification)
    assertEquals(ValueOrigin.SELF_DECLARED, f.origin)
  }

  @Test
  fun `a number must match a whole number of the excerpt`() {
    fun problem(sentence: String, excerpt: String) =
      EvidenceRules.claimProblem(sentence, listOf(excerpt), "Acme")
    assertTrue(problem("Acme supports 4 tokens.", "Acme supports 4000 tokens.") != null)
    assertTrue(problem("Acme supports 400 tokens.", "Acme supports 4,000 tokens.") != null)
    assertEquals(null, problem("Acme supports 4,000 tokens.", "Acme supports 4000 tokens."))
    assertEquals(null, problem("Acme supports 4000 tokens.", "Acme supports 4k tokens."))
    assertEquals(null, problem("Acme supports 4.5 tokens.", "Acme supports 4.5 tokens."))
    assertTrue(problem("Acme supports 4 tokens.", "Acme supports 4.5 tokens.") != null)
    // A name is a whole word too: "Go" is not in "Google".
    assertTrue(problem("Acme runs on Go servers.", "Acme runs on Google servers.") != null)
  }
}
