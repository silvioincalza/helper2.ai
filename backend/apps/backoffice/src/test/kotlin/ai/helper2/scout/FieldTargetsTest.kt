package ai.helper2.scout

import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldTargetsTest {

  private fun result(vararg fields: CheckedField) =
    ValidationResult(
      EnrichmentOutcome.VALIDATED,
      fields.toList(),
      emptyList(),
      emptyList(),
      emptyList(),
    )

  @Test
  fun `a product needs pricing, platforms and use case`() {
    assertEquals(
      setOf("pricing", "platforms", "useCase"),
      FieldTargets.forType(ResourceType.PRODUCT),
    )
  }

  @Test
  fun `a code resource needs licence, open source, platforms and use case`() {
    for (type in listOf(ResourceType.MCP_SERVER, ResourceType.SKILL)) {
      assertEquals(
        setOf("license", "openSource", "platforms", "useCase"),
        FieldTargets.forType(type),
      )
    }
  }

  @Test
  fun `a library needs licence, open source and platforms, not a use case`() {
    assertEquals(
      setOf("license", "openSource", "platforms"),
      FieldTargets.forType(ResourceType.LIBRARY),
    )
  }

  @Test
  fun `a model needs licence, open source and use case, not pricing`() {
    assertEquals(
      setOf("license", "openSource", "useCase"),
      FieldTargets.forType(ResourceType.MODEL),
    )
  }

  @Test
  fun `reading types have no targets, their facts come from the sources`() {
    for (type in
      listOf(
        ResourceType.ARTICLE,
        ResourceType.PAPER,
        ResourceType.NEWS,
        ResourceType.EVENT,
        ResourceType.COURSE,
        ResourceType.OTHER,
      )) {
      assertTrue(FieldTargets.forType(type).isEmpty(), "$type")
    }
  }

  @Test
  fun `judgement fields are never targets`() {
    for (type in ResourceType.entries) {
      assertTrue(EvidenceValidator.JUDGEMENT_FIELDS.none { it in FieldTargets.forType(type) })
    }
  }

  @Test
  fun `missing leaves out what the sources state and what the run verified`() {
    val missing =
      FieldTargets.missing(
        ResourceType.MCP_SERVER,
        setOf("license"),
        result(
          CheckedField("platforms", "cli", Verification.VERIFIED, emptyList()),
          CheckedField("useCase", "coding", Verification.UNVERIFIED, emptyList()),
          CheckedField(
            "audience",
            "BUILD_WITH_AI",
            Verification.UNVERIFIED,
            emptyList(),
            ValueOrigin.AI_ESTIMATED,
          ),
        ),
      )
    assertEquals(setOf("openSource", "useCase"), missing)
  }

  @Test
  fun `a platform or a use case the sources state is not missing`() {
    val input =
      EnrichmentInput(
        resourceTitle = "wakong",
        materials = emptyList(),
        sourceFacts = mapOf("license" to "MIT"),
        sourceStated = mapOf("platforms" to setOf("python"), "useCase" to emptySet()),
      )
    assertEquals(setOf("license", "platforms"), input.statedBySource)
    assertEquals(
      setOf("openSource", "useCase"),
      FieldTargets.missing(ResourceType.MCP_SERVER, input.statedBySource, emptySet()),
    )
  }

  @Test
  fun `a self-declared verified fact counts as filled`() {
    val missing =
      FieldTargets.missing(
        ResourceType.PROMPT,
        emptySet(),
        result(
          CheckedField(
            "useCase",
            "writing",
            Verification.VERIFIED,
            emptyList(),
            ValueOrigin.SELF_DECLARED,
          )
        ),
      )
    assertTrue(missing.isEmpty())
  }
}
