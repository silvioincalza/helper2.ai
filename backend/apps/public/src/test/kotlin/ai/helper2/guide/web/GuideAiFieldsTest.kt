package ai.helper2.guide.web

import ai.helper2.api.generated.model.GuideResponseDto
import ai.helper2.api.generated.model.GuideStreamCheckedDto
import ai.helper2.api.generated.model.GuideStreamChosenDto
import ai.helper2.api.generated.model.GuideStreamClarifyDto
import ai.helper2.api.generated.model.GuideStreamModelStepDto
import ai.helper2.api.generated.model.GuideStreamPlannedDto
import ai.helper2.api.generated.model.GuideStreamRetrievedDto
import ai.helper2.api.generated.model.GuideStreamUnderstoodDto
import ai.helper2.api.generated.model.GuideStreamValidatedDto
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideAiFieldsTest {
  private val notAiWritten: Map<String, String> =
    listOf(
        WORDS_OF_THE_SOURCE to
          listOf(
            "recommendations[].title",
            "recommendations[].url",
            "recommendations[].description",
            "recommendations[].quote",
            "recommendations[].checks[].quote",
            "comparison.subjects[].title",
            "comparison.subjects[].url",
            "comparison.rows[].cells[].quote",
            "explanation.sentences[].quote",
            "explanation.sentences[].title",
            "explanation.sentences[].url",
            "trail.removed[].title",
          ),
        WORDS_OF_THE_ASKER to listOf("subjects[]", "comparison.subjects[].name"),
        OUR_WORDS to listOf("notices[].message", "sources[].name", "sources[].message"),
        READ_BY_CODE to listOf("intent", "window.from", "window.to"),
        SET_BY_CODE to
          listOf(
            "status",
            "generatedAt",
            "notices[].retryAt",
            "candidatesConsidered",
            "rejected",
            "recommendations[].resourceId",
            "recommendations[].type",
            "recommendations[].sourceIds[]",
            "recommendations[].aiReasoned",
            "recommendations[].reasonCheck",
            "recommendations[].checks[].status",
            "recommendations[].compatibleWith[].tool",
            "recommendations[].compatibleWith[].label",
            "recommendations[].compatibleWith[].basis",
            "recommendations[].compatibleWith[].evidence",
            "recommendations[].compatibleWith[].sourceId",
            "model.provider",
            "model.fastModel",
            "model.reasoningModel",
            "aiContent.aiGenerated",
            "aiContent.provider",
            "aiContent.models[]",
            "aiContent.fields[]",
            "aiContent.policy",
            "sources[].sourceId",
            "sources[].mode",
            "sources[].outcome",
            "sources[].servedFrom",
            "sources[].resultCount",
            "sources[].latencyMs",
            "sources[].checkedAt",
            "sources[].cachedAt",
            "sources[].skipReason",
            "notices[].code",
            "comparison.subjects[].key",
            "comparison.subjects[].resourceId",
            "comparison.rows[].cells[].subjectKey",
            "comparison.rows[].cells[].reasonCheck",
            "explanation.sentences[].resourceId",
            "explanation.sentences[].reasonCheck",
            "webSearches.limit",
            "webSearches.remaining",
            "trail.removed[].check",
          ),
      )
      .flatMap { (why, paths) -> paths.map { it to why } }
      .toMap()

  private val streamSteps: Map<Class<*>, String> =
    mapOf(
      GuideStreamPlannedDto::class.java to "",
      GuideStreamUnderstoodDto::class.java to "",
      GuideStreamClarifyDto::class.java to "clarification.",
      GuideStreamRetrievedDto::class.java to "",
      GuideStreamChosenDto::class.java to "",
      GuideStreamValidatedDto::class.java to "",
      GuideStreamCheckedDto::class.java to "",
      GuideStreamModelStepDto::class.java to "",
    )

  private val streamOnly =
    setOf(
      "GuideStreamPlannedDto model",
      "GuideStreamClarifyDto aiContent.aiGenerated",
      "GuideStreamClarifyDto aiContent.provider",
      "GuideStreamClarifyDto aiContent.models[]",
      "GuideStreamClarifyDto aiContent.fields[]",
      "GuideStreamClarifyDto aiContent.policy",
      "GuideStreamRetrievedDto candidates",
      "GuideStreamChosenDto proposed",
      "GuideStreamChosenDto model",
      "GuideStreamValidatedDto kept",
      "GuideStreamCheckedDto supported",
      "GuideStreamCheckedDto removed",
      "GuideStreamCheckedDto notChecked",
      "GuideStreamModelStepDto step",
    )

  private val answerFields = leafPaths(GuideResponseDto::class.java)

  @Test
  fun `every field of the answer is classed as written by a model or not`() {
    val unclassed = answerFields.filter {
      it !in GuideDtoMapper.AI_WRITTEN_FIELDS && it !in notAiWritten
    }

    assertEquals(
      emptyList(),
      unclassed,
      "add each to GuideDtoMapper.AI_WRITTEN_FIELDS (and mark it in the UI) or to notAiWritten",
    )
  }

  @Test
  fun `no field is classed twice, and every classed field still exists`() {
    assertEquals(emptyList(), GuideDtoMapper.AI_WRITTEN_FIELDS.filter { it in notAiWritten })
    assertEquals(emptyList(), GuideDtoMapper.AI_WRITTEN_FIELDS.filter { it !in answerFields })
    assertEquals(emptyList(), notAiWritten.keys.filter { it !in answerFields })
  }

  @Test
  fun `every field of a streamed step repeats a classed field of the answer, or is a code`() {
    val stepFields = streamSteps.flatMap { (type, answerPrefix) ->
      leafPaths(type).map { Triple(type.simpleName, it, answerPrefix + it) }
    }
    val unclassed =
      stepFields
        .filter { (step, field, _) -> "$step $field" !in streamOnly }
        .filter { (_, _, inAnswer) -> inAnswer !in answerFields }
        .map { (step, field, _) -> "$step $field" }

    assertEquals(emptyList(), unclassed)
    assertTrue(
      streamOnly.all { entry -> stepFields.any { (step, field, _) -> "$step $field" == entry } },
      "a stream-only field that no longer exists",
    )
  }

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
      "the source's own text: titles, links and descriptions as fetched; a quote is kept only " +
        "when code finds it word for word in that text"
    const val WORDS_OF_THE_ASKER =
      "the asker's own words: code keeps a subject only when the question contains it " +
        "(QuestionReader.groundedSubjects)"
    const val OUR_WORDS =
      "our own words; the guide never sends QUERY_EXPANDED, whose terms can hold a model " +
        "translation (GuideDtoMapper.notices)"
    const val READ_BY_CODE =
      "a code or dates, not text: the planner's intent and period count only when the " +
        "question's own words support them, and code turns the period into dates (QuestionReader)"
    const val SET_BY_CODE = "a code, id, number, date or flag set by code"
  }
}
