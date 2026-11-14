package ai.helper2.guide.web

import ai.helper2.api.common.model.NoticeDto
import ai.helper2.api.generated.model.GuideTrailRemovedDto
import ai.helper2.guide.Clarification
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
import ai.helper2.shared.ValueOrigin
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideDtoMapperTest {

  private val mapper =
    GuideDtoMapper(LlmSettings("openai", "nebius-token-factory", "nano", "super"))
  private val now = Instant.parse("2026-10-30T12:00:00Z")

  private fun answer(modelUsed: Boolean, planned: Boolean) =
    GuideAnswer(
      status = GuideStatus.LLM_UNAVAILABLE,
      searchQuery = "speech recognition",
      recommendations = emptyList(),
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
  }

  @Test
  fun `the marker lists only the AI-written fields the answer holds`() {
    val planned = mapper.toDto(answer(modelUsed = true, planned = true)).aiContent!!.fields

    assertEquals(
      listOf("searchQuery", "constraints[]", "recommendations[].checks[].constraint"),
      planned,
    )
  }

  @Test
  fun `a fallback answer's fixed reason is not listed as AI-written`() {
    val fallback =
      Recommendation(
        merged("https://github.com/a/fallback"),
        "Top search result (no AI reasoning available).",
        "a",
        aiReasoned = false,
      )
    val dto =
      mapper.toDto(
        answer(modelUsed = false, planned = false).copy(recommendations = listOf(fallback))
      )

    assertNull(dto.aiContent)
  }

  @Test
  fun `a question back written by code from result names carries no marker`() {
    val ours = Clarification("Which Gemini do you mean?", listOf("a (model)", "b (tool)"), false)
    val planners = Clarification("For what?", listOf("coding", "agents"))

    assertNull(mapper.clarifyMarker(ours))
    assertEquals(listOf("question", "options[]"), mapper.clarifyMarker(planners)!!.fields)
    assertEquals(
      listOf("searchQuery", "constraints[]"),
      mapper.plannedMarker("speech recognition", listOf("free"))!!.fields,
    )
    assertEquals(listOf("constraints[]"), mapper.plannedMarker("", listOf("free"))!!.fields)
    assertNull(mapper.plannedMarker("", emptyList()), "the planner wrote nothing that is shown")
  }

  @Test
  fun `every trail check has its own contract code, a pick off the need included`() {
    val trail =
      ResearchTrail(emptyList(), TrailCheck.entries.map { RemovedSuggestion(it.name, it) })
    val dto = mapper.toDto(answer(modelUsed = true, planned = true).copy(trail = trail))

    assertEquals(
      TrailCheck.entries.map { it.name },
      dto.trail!!.removed.map { it.check.value },
    )
    assertTrue(GuideTrailRemovedDto.CheckEnum.OFF_NEED in dto.trail!!.removed.map { it.check })
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
          .copy(notices = listOf(Notice(NoticeCode.CACHE_UNAVAILABLE, "cache down")))
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

  @Test
  fun `a guide answer never carries the query expansion, whose terms can hold a model translation`() {
    val search =
      SearchOutcome.empty(now)
        .copy(
          notices =
            listOf(
              Notice(NoticeCode.QUERY_EXPANDED, "Also searched for: server mcp"),
              Notice(NoticeCode.CACHE_UNAVAILABLE, "cache down"),
            )
        )

    val dto = mapper.toDto(answer(modelUsed = true, planned = true).copy(search = search))

    assertEquals(listOf(NoticeDto.CodeEnum.CACHE_UNAVAILABLE), dto.notices.map { it.code })
  }

  @Test
  fun `a description a model wrote is not sent with a pick, the source's own is`() {
    val written = merged("https://github.com/a/written")
    val ai = written.copy(description = "A summary", descriptionOrigin = ValueOrigin.AI_SUMMARY)
    val own = written.copy(description = "Its own words")
    val withPicks =
      answer(modelUsed = true, planned = true)
        .copy(
          recommendations =
            listOf(
              Recommendation(ai, "Fits", "", aiReasoned = true),
              Recommendation(own, "Fits", "", aiReasoned = true),
            )
        )

    val picks = mapper.toDto(withPicks).recommendations

    assertNull(picks[0].description)
    assertEquals("Its own words", picks[1].description)
  }
}
