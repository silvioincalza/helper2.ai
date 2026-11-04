package ai.helper2.guide.web

import ai.helper2.api.generated.model.GuideTrailRemovedDto
import ai.helper2.api.generated.model.NoticeDto
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.Recommendation
import ai.helper2.guide.RemovedSuggestion
import ai.helper2.guide.ResearchTrail
import ai.helper2.guide.TrailCheck
import ai.helper2.platform.llm.LlmSettings
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.ResultMerger
import ai.helper2.search.SearchOutcome
import ai.helper2.search.contexts
import ai.helper2.search.hit
import ai.helper2.shared.ResourceType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI-content marker (ADR 0038): present whenever a model wrote text in the answer, naming only
 * the models that did. "Works with" flags on every pick (ADR 0013), from the same rules as the
 * result cards.
 */
class GuideDtoMapperTest {

  private val mapper =
    GuideDtoMapper(LlmSettings("openai", "nebius-token-factory", "nano", "super"))
  private val now = Instant.parse("2026-10-30T12:00:00Z")

  private fun answer(modelUsed: Boolean, planned: Boolean) =
    GuideAnswer(
      status = GuideStatus.LLM_UNAVAILABLE,
      searchQuery = "speech recognition",
      recommendations = emptyList(),
      prompt = null,
      modelUsed = modelUsed,
      candidatesConsidered = 0,
      rejected = 0,
      search = SearchOutcome.empty(now),
      generatedAt = now,
      constraints = listOf("free"),
      planned = planned,
    )

  @Test
  fun `the planner alone wrote text, so it is marked with the fast model only, no answering model shown`() {
    val dto = mapper.toDto(answer(modelUsed = false, planned = true))

    assertEquals(listOf("nano"), dto.aiContent!!.models)
    assertTrue("constraints[]" in dto.aiContent!!.fields)
    assertNull(dto.model)
  }

  @Test
  fun `the answering call ran, so both models are named`() {
    val dto = mapper.toDto(answer(modelUsed = true, planned = true))

    assertEquals(listOf("nano", "super"), dto.aiContent!!.models)
    assertTrue("comparison.rows[].aspect" in dto.aiContent!!.fields)
    assertTrue("recommendations[].checks[].constraint" in dto.aiContent!!.fields)
  }

  @Test
  fun `the trail's queries are marked AI-written, and each removed suggestion carries only its title and check`() {
    val trail =
      ResearchTrail(
        listOf("speech recognition"),
        listOf(RemovedSuggestion("promo", TrailCheck.CONSTRAINT_NOT_MET)),
      )
    val dto = mapper.toDto(answer(modelUsed = true, planned = true).copy(trail = trail))

    assertTrue("trail.queries[]" in dto.aiContent!!.fields)
    assertTrue(
      dto.aiContent!!.fields.none { it.startsWith("trail.removed") },
      "titles come from the sources, checks are codes",
    )
    assertEquals(listOf("speech recognition"), dto.trail!!.queries)
    assertEquals("promo", dto.trail!!.removed.single().title)
    assertEquals(
      GuideTrailRemovedDto.CheckEnum.CONSTRAINT_NOT_MET,
      dto.trail!!.removed.single().check,
    )
  }

  @Test
  fun `the name and the search of a not-found notice are their own fields, marked AI-written, the message is not`() {
    val notice =
      Notice(
        NoticeCode.GUIDE_SUBJECT_NOT_FOUND,
        "The name in the question was not found.",
        subject = "Frobnicator",
        query = "pdf chat",
      )
    val dto = mapper.toDto(answer(modelUsed = true, planned = true).copy(notices = listOf(notice)))

    val n = dto.notices.single()
    assertEquals(NoticeDto.CodeEnum.GUIDE_SUBJECT_NOT_FOUND, n.code)
    assertEquals("Frobnicator", n.subject)
    assertEquals("pdf chat", n.query)
    assertEquals("The name in the question was not found.", n.message)
    assertTrue(dto.aiContent!!.fields.containsAll(listOf("notices[].subject", "notices[].query")))
    assertTrue("notices[].message" !in dto.aiContent!!.fields)
  }

  @Test
  fun `a search notice has no name and no search`() {
    val dto =
      mapper.toDto(
        answer(modelUsed = true, planned = true)
          .copy(
            notices =
              listOf(
                Notice(
                  NoticeCode.CACHE_UNAVAILABLE,
                  "cache down",
                )
              )
          )
      )

    assertNull(dto.notices.single().subject)
    assertNull(dto.notices.single().query)
  }

  @Test
  fun `no model wrote anything, so there is no marker`() {
    assertNull(mapper.toDto(answer(modelUsed = false, planned = false)).aiContent)
  }

  private fun merged(
    url: String,
    tags: Set<String> = emptySet(),
    type: ResourceType = ResourceType.LIBRARY,
  ) =
    ResultMerger()
      .merge(listOf(hit("github", url, tags = tags, type = type)), contexts("github"))
      .single()

  @Test
  fun `every pick carries its works-with flags, an AI pick and a fallback pick alike`() {
    val server = merged("https://github.com/a/server", tags = setOf("mcp"))
    val model = merged("https://github.com/a/model", type = ResourceType.MODEL)
    val withPicks =
      answer(modelUsed = true, planned = true)
        .copy(
          status = GuideStatus.OK,
          recommendations =
            listOf(
              Recommendation(server, "Connects tools", "An MCP server", aiReasoned = true),
              Recommendation(model, "", "", aiReasoned = false),
            ),
        )

    val picks = mapper.toDto(withPicks).recommendations

    assertEquals(listOf("mcp"), picks[0].compatibleWith.map { it.tool })
    assertEquals("mcp", picks[0].compatibleWith.single().evidence)
    assertEquals(emptyList(), picks[1].compatibleWith, "no flag is an empty list, always sent")
  }

  @Test
  fun `the flags are not AI-written fields`() {
    assertTrue(GuideDtoMapper.AI_WRITTEN_FIELDS.none { "compatibleWith" in it })
  }
}
