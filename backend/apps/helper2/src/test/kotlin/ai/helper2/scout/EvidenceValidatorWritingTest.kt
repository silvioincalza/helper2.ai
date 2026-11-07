package ai.helper2.scout

import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0062: an article, a news item or a paper is writing about other resources. Its facts are who
 * it is for, how technical it is and its topic; the pricing or licence it quotes belong to what it
 * is about, which it links to with ABOUT.
 */
class EvidenceValidatorWritingTest {

  private val ev = UUID.fromString("00000000-0000-0000-0000-00000000000e")
  private val gemini = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
  private val text =
    "Google has barged in with its latest Gemini 4 Carbon model, boasting of top benchmark " +
      "scores in key areas like cybersecurity and coding. It comes with a free plan for developers."

  private fun input(type: ResourceType, known: Map<String, UUID> = emptyMap()) =
    EnrichmentInput(
      resourceTitle = "Google Gemini Carbon promises a significant leap in AI coding",
      materials =
        listOf(
          Material(
            ev,
            URI("https://www.engadget.com/2300677/google-gemini-carbon-promises"),
            Instant.parse("2026-11-02T06:00:00Z"),
            text,
          )
        ),
      sourceFacts = mapOf("type" to type.name),
      knownResources = known,
      type = type,
    )

  private fun cite(excerpt: String) = listOf(Citation(ev.toString(), excerpt))

  private val validator = EvidenceValidator()

  @Test
  fun `news keeps no pricing of what it talks about, even with a quote that states it`() {
    val r =
      validator.validate(
        input(ResourceType.NEWS),
        EnrichmentProposal(
          fields =
            listOf(
              ProposedField("pricing", "FREEMIUM", cite("It comes with a free plan for developers"))
            )
        ),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
    assertTrue(r.errors.any { "field pricing" in it && "NEWS" in it }, r.errors.toString())
  }

  @Test
  fun `news keeps its topic, audience and tech level`() {
    val r =
      validator.validate(
        input(ResourceType.NEWS),
        EnrichmentProposal(
          fields =
            listOf(
              ProposedField(
                "useCase",
                "coding",
                cite("top benchmark scores in key areas like cybersecurity and coding"),
              ),
              ProposedField("audience", "BUILD_WITH_AI", emptyList()),
            )
        ),
      )
    assertEquals(Verification.VERIFIED, r.fields.first { it.field == "useCase" }.verification)
    assertEquals(ValueOrigin.AI_ESTIMATED, r.fields.first { it.field == "audience" }.origin)
  }

  @Test
  fun `the model's type for writing is refused, not left as a conflict`() {
    val r =
      validator.validate(
        input(ResourceType.NEWS),
        EnrichmentProposal(
          fields =
            listOf(
              ProposedField("type", "MODEL", cite("its latest Gemini 4 Carbon model, boasting"))
            )
        ),
      )
    assertEquals(Verification.REJECTED, r.fields.single().verification)
  }

  @Test
  fun `an article links to the model it is about, verified when the catalog knows it`() {
    val r =
      validator.validate(
        input(ResourceType.ARTICLE, mapOf("gemini 4 carbon" to gemini)),
        EnrichmentProposal(
          relationships =
            listOf(
              ProposedRelationship(
                "ABOUT",
                "Gemini 4 Carbon",
                cite("Google has barged in with its latest Gemini 4 Carbon model"),
              )
            )
        ),
      )
    val about = r.relationships.single()
    assertEquals(Verification.VERIFIED, about.verification)
    assertEquals(gemini, about.targetId)
  }

  @Test
  fun `an unknown subject stays a candidate for review`() {
    val r =
      validator.validate(
        input(ResourceType.NEWS),
        EnrichmentProposal(
          relationships =
            listOf(
              ProposedRelationship(
                "ABOUT",
                "Gemini 4 Carbon",
                cite("Google has barged in with its latest Gemini 4 Carbon model"),
              )
            )
        ),
      )
    assertEquals(Verification.UNVERIFIED, r.relationships.single().verification)
  }

  @Test
  fun `a subject the quote does not name is refused`() {
    val r =
      validator.validate(
        input(ResourceType.NEWS),
        EnrichmentProposal(
          relationships =
            listOf(
              ProposedRelationship(
                "ABOUT",
                "Gemini 3 Pro",
                cite("Google has barged in with its latest Gemini 4 Carbon model"),
              )
            )
        ),
      )
    assertEquals(Verification.REJECTED, r.relationships.single().verification)
  }

  @Test
  fun `writing has no other relationship kind, and a product has no ABOUT`() {
    val quote = cite("Google has barged in with its latest Gemini 4 Carbon model")
    val news =
      validator.validate(
        input(ResourceType.NEWS),
        EnrichmentProposal(
          relationships = listOf(ProposedRelationship("INTEGRATES_WITH", "Gemini 4 Carbon", quote))
        ),
      )
    val product =
      validator.validate(
        input(ResourceType.PRODUCT),
        EnrichmentProposal(
          relationships = listOf(ProposedRelationship("ABOUT", "Gemini 4 Carbon", quote))
        ),
      )
    assertEquals(Verification.REJECTED, news.relationships.single().verification)
    assertEquals(Verification.REJECTED, product.relationships.single().verification)
  }

  @Test
  fun `at most three subjects per run`() {
    val many =
      "Gemini 4 Carbon, Claude Opus 5, GPT-6 and Llama 5 were all compared on coding tasks today."
    val r =
      validator.validate(
        input(ResourceType.ARTICLE)
          .copy(materials = listOf(Material(ev, URI("https://x.test/a"), Instant.EPOCH, many))),
        EnrichmentProposal(
          relationships =
            listOf("Gemini 4 Carbon", "Claude Opus 5", "GPT-6", "Llama 5").map {
              ProposedRelationship("ABOUT", it, cite(many.substring(0, 60)))
            }
        ),
      )
    assertEquals(3, r.relationships.count { it.verification != Verification.REJECTED })
  }

  @Test
  fun `which types are writing`() {
    assertEquals(
      setOf(ResourceType.ARTICLE, ResourceType.NEWS, ResourceType.PAPER),
      ResourceType.entries.filter(FieldsByType::isWriting).toSet(),
    )
  }

  /** ADR 0066: the model's value of a field an admin set is dropped, with no error to review. */
  @Test
  fun `a field an admin set is not a question for the model`() {
    val r =
      validator.validate(
        input(ResourceType.PRODUCT).copy(curatedFields = setOf("pricing")),
        EnrichmentProposal(
          fields =
            listOf(
              ProposedField(
                "pricing",
                "FREEMIUM",
                cite("It comes with a free plan for developers"),
              ),
              ProposedField(
                "useCase",
                "coding",
                cite("top benchmark scores in key areas like cybersecurity and coding"),
              ),
            )
        ),
      )
    assertEquals(Verification.REJECTED, r.fields.first { it.field == "pricing" }.verification)
    assertEquals(Verification.VERIFIED, r.fields.first { it.field == "useCase" }.verification)
    assertTrue(r.errors.none { "pricing" in it }, r.errors.toString())
    assertEquals(EnrichmentOutcome.VALIDATED, r.outcome)
  }
}
