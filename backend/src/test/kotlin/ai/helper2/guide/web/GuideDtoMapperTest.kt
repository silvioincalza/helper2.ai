package ai.helper2.guide.web

import ai.helper2.api.generated.model.GuideTrailRemovedDto
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.RemovedSuggestion
import ai.helper2.guide.ResearchTrail
import ai.helper2.guide.TrailCheck
import ai.helper2.guide.GuideStatus
import ai.helper2.platform.llm.LlmSettings
import ai.helper2.search.SearchOutcome
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The AI-content marker (ADR 0038): present whenever a model wrote text in the answer, naming only the models that did. */
class GuideDtoMapperTest {

    private val mapper = GuideDtoMapper(LlmSettings("openai", "nebius-token-factory", "nano", "super"))
    private val now = Instant.parse("2026-10-30T12:00:00Z")

    private fun answer(modelUsed: Boolean, planned: Boolean) = GuideAnswer(
        status = GuideStatus.LLM_UNAVAILABLE, searchQuery = "speech recognition", recommendations = emptyList(), prompt = null,
        modelUsed = modelUsed, candidatesConsidered = 0, rejected = 0, search = SearchOutcome.empty(now), generatedAt = now,
        constraints = listOf("free"), planned = planned,
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
        val trail = ResearchTrail(listOf("speech recognition"), listOf(RemovedSuggestion("promo", TrailCheck.CONSTRAINT_NOT_MET)))
        val dto = mapper.toDto(answer(modelUsed = true, planned = true).copy(trail = trail))

        assertTrue("trail.queries[]" in dto.aiContent!!.fields)
        assertTrue(dto.aiContent!!.fields.none { it.startsWith("trail.removed") }, "titles come from the sources, checks are codes")
        assertEquals(listOf("speech recognition"), dto.trail!!.queries)
        assertEquals("promo", dto.trail!!.removed.single().title)
        assertEquals(GuideTrailRemovedDto.CheckEnum.CONSTRAINT_NOT_MET, dto.trail!!.removed.single().check)
    }

    @Test
    fun `no model wrote anything, so there is no marker`() {
        assertNull(mapper.toDto(answer(modelUsed = false, planned = false)).aiContent)
    }
}
