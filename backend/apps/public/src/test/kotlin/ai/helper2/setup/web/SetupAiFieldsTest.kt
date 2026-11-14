package ai.helper2.setup.web

import ai.helper2.api.generated.model.SetupGuideDto
import ai.helper2.api.generated.model.ToolGuideDto
import ai.helper2.setup.Code
import ai.helper2.setup.GuideSection
import ai.helper2.setup.GuideSectionKind
import ai.helper2.setup.GuideStep
import ai.helper2.setup.StepTexts
import ai.helper2.setup.ToolGuideAnswer
import ai.helper2.setup.ToolGuideStatus
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class SetupAiFieldsTest {
  private val toolGuideNotAiWritten: Map<String, String> =
    classed(
      WORDS_OF_THE_SOURCE to
        listOf("title", "sections[].steps[].quote", "sections[].steps[].code.text"),
      OUR_WORDS to listOf("unavailable"),
      CHECKED_BY_CODE to listOf("sections[].kind", "sections[].steps[].source"),
      SET_BY_CODE to
        listOf(
          "resourceId",
          "status",
          "retryAt",
          "sections[].steps[].caution",
          "documents[].key",
          "documents[].kind",
          "documents[].url",
          "documents[].retrievedAt",
          "model",
          "aiContent.aiGenerated",
          "aiContent.provider",
          "aiContent.models[]",
          "aiContent.fields[]",
          "aiContent.policy",
          "rejected",
          "generatedAt",
        ),
    )

  private val setupNotAiWritten: Map<String, String> =
    classed(
      WORDS_OF_THE_SOURCE to
        listOf(
          "title",
          "version",
          "options[].url",
          "options[].registryType",
          "options[].identifier",
          "options[].settings[].name",
          "options[].settings[].description",
          "options[].settings[].value",
          "sources[].url",
        ),
      OUR_WORDS to
        listOf(
          "options[].label",
          "options[].runtime",
          "options[].requires",
          "clients[].name",
          "clients[].docsUrl",
          "clients[].snippets[].where",
          "clients[].snippets[].text",
          "clients[].snippets[].note",
        ),
      SET_BY_CODE to
        listOf(
          "resourceId",
          "options[].id",
          "options[].kind",
          "options[].transport",
          "options[].settings[].kind",
          "options[].settings[].required",
          "options[].settings[].secret",
          "clients[].id",
          "clients[].snippets[].optionId",
          "clients[].snippets[].supported",
          "clients[].snippets[].language",
          "sources[].sourceId",
          "sources[].retrievedAt",
        ),
    )

  private val toolGuideFields = leafPaths(ToolGuideDto::class.java)

  @Test
  fun `every field of the tool guide is classed as written by a model or not`() {
    val unclassed = toolGuideFields.filter {
      it !in SetupController.AI_WRITTEN_FIELDS && it !in toolGuideNotAiWritten
    }

    assertEquals(
      emptyList(),
      unclassed,
      "add each to SetupController.AI_WRITTEN_FIELDS (and mark it in the UI) or to " +
        "toolGuideNotAiWritten",
    )
  }

  @Test
  fun `no tool guide field is classed twice, and every classed field still exists`() {
    assertEquals(
      emptyList(),
      SetupController.AI_WRITTEN_FIELDS.filter { it in toolGuideNotAiWritten },
    )
    assertEquals(emptyList(), SetupController.AI_WRITTEN_FIELDS.filter { it !in toolGuideFields })
    assertEquals(emptyList(), toolGuideNotAiWritten.keys.filter { it !in toolGuideFields })
  }

  @Test
  fun `no field of the setup facts is written by a model`() {
    assertEquals(leafPaths(SetupGuideDto::class.java).sorted(), setupNotAiWritten.keys.sorted())
  }

  private fun step(text: String?, language: String? = null) =
    GuideStep(text, "readme", "a quote", language?.let { Code(it, "npm i x") }, false)

  private fun answer(
    status: ToolGuideStatus,
    model: String?,
    vararg sections: List<GuideStep>,
  ) =
    ToolGuideAnswer(
      status,
      sections.map { GuideSection(GuideSectionKind.INSTALL, it) },
      emptyList(),
      model,
      0,
      Instant.EPOCH,
    )

  @Test
  fun `the marker names the model and the AI-written fields only when steps are shown`() {
    val shown = answer(ToolGuideStatus.OK, "nemotron", listOf(step("Install it.", "bash")))

    val marker = SetupController.marker(shown, "nebius")!!
    assertEquals(listOf("nemotron"), marker.models)
    assertEquals("nebius", marker.provider)
    assertEquals(SetupController.AI_WRITTEN_FIELDS, marker.fields)
    assertEquals("/mission", marker.policy)
    assertNull(SetupController.marker(answer(ToolGuideStatus.NO_STEPS, "nemotron"), "nebius"))
    assertNull(SetupController.marker(answer(ToolGuideStatus.LLM_UNAVAILABLE, null), "nebius"))
  }

  @Test
  fun `a step that shows only its source is not named in the marker`() {
    val mixed =
      answer(
        ToolGuideStatus.OK,
        "nemotron",
        listOf(step("Install it.")),
        listOf(step(null), step("Run it.")),
      )

    assertEquals(
      listOf("sections[0].steps[0].text", "sections[1].steps[1].text"),
      SetupController.marker(mixed, "nebius")!!.fields,
    )
  }

  @Test
  fun `with no step text shown, only a code language a model named is marked, or nothing`() {
    val languageOnly =
      answer(ToolGuideStatus.OK, "nemotron", listOf(step(null, "bash"), step(null)))
    val sourceOnly = answer(ToolGuideStatus.OK, "nemotron", listOf(step(null)))

    assertEquals(
      listOf(SetupController.CODE_LANGUAGE),
      SetupController.marker(languageOnly, "nebius")!!.fields,
    )
    assertNull(SetupController.marker(sourceOnly, "nebius"))
  }

  @Test
  fun `a step that shows only its source is sent with a null text and its quote and command`() {
    val answer = answer(ToolGuideStatus.OK, "nemotron", listOf(step(null, "bash"), step("Run it.")))

    val json =
      JsonMapper.builder()
        .build()
        .writeValueAsString(SetupController.dto(UUID.randomUUID(), "acme", answer, "nebius"))

    assertTrue(json.contains("\"text\":null,\"quote\":\"a quote\""), json)
    assertTrue(json.contains("\"npm i x\""), json)
    assertTrue(json.contains("\"sections[0].steps[1].text\""), json)
  }

  @Test
  fun `a verified guide marks its step texts, a source-only guide never does`() {
    val written = listOf(step("Install it.", "bash"), step("Run it."))
    val verified = answer(ToolGuideStatus.OK, "nemotron", written)
    val sourceOnly =
      answer(
        ToolGuideStatus.OK,
        "nemotron",
        StepTexts.sourceOnly(listOf(GuideSection(GuideSectionKind.INSTALL, written)))
          .single()
          .steps,
      )

    assertEquals(
      listOf(SetupController.STEP_TEXT, SetupController.CODE_LANGUAGE),
      SetupController.marker(verified, "nebius")!!.fields,
    )
    val fields = SetupController.marker(sourceOnly, "nebius")!!.fields
    assertEquals(listOf(SetupController.CODE_LANGUAGE), fields)
    assertTrue(fields.none { it.endsWith(".text") }, fields.toString())
    assertNull(
      SetupController.marker(
        answer(
          ToolGuideStatus.OK,
          "nemotron",
          StepTexts.sourceOnly(
              listOf(GuideSection(GuideSectionKind.INSTALL, listOf(step("Run it."))))
            )
            .single()
            .steps,
        ),
        "nebius",
      )
    )
  }

  private fun classed(vararg groups: Pair<String, List<String>>): Map<String, String> =
    groups.flatMap { (why, paths) -> paths.map { it to why } }.toMap()

  private fun leafPaths(type: Class<*>, prefix: String = ""): List<String> =
    type.declaredFields
      .filter { !Modifier.isStatic(it.modifiers) }
      .flatMap { field -> paths(field, prefix + field.name) }

  private fun paths(field: Field, path: String): List<String> {
    val generic = field.genericType
    if (generic is ParameterizedType && List::class.java.isAssignableFrom(field.type)) {
      val element = generic.actualTypeArguments.single() as Class<*>
      return if (isDto(element)) leafPaths(element, "$path[].") else listOf("$path[]")
    }
    return if (isDto(field.type)) leafPaths(field.type, "$path.") else listOf(path)
  }

  private fun isDto(type: Class<*>) = type.name.startsWith("ai.helper2.api.") && !type.isEnum

  private companion object {
    const val WORDS_OF_THE_SOURCE =
      "the source's own text: titles, links and fields as fetched; a quote or command is kept " +
        "only when code finds it word for word in the cited document (ToolGuideValidator)"
    const val OUR_WORDS =
      "our own words or formats: labels, client names and links, and snippets code writes " +
        "from the source's facts (Clients, Formats)"
    const val CHECKED_BY_CODE =
      "a code the model picks, kept only when code knows it: a section kind, a document key"
    const val SET_BY_CODE = "a code, id, number, date or flag set by code"
  }
}
