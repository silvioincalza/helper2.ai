package ai.helper2.guide.web

import ai.helper2.guide.Candidate
import ai.helper2.guide.Clarification
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.usecase.AskGuide
import ai.helper2.platform.SignedInUser
import ai.helper2.platform.llm.LlmSettings
import ai.helper2.platform.resilience.Resilience4jPolicies
import ai.helper2.search.CachedHits
import ai.helper2.search.NOW
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The guide's latency target (ADR 0026): `helper2.guide.first_useful{intent}`, the time from the request to the
 * first event that shows the asker something real — `retrieved` (the results the guide reads) or `clarify` (the
 * question back).
 */
class GuideFirstUsefulTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val meters = SimpleMeterRegistry()
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun shutdown() {
        executor.shutdownNow()
        closers.forEach { it() }
    }

    private val source = object : SearchSource {
        override val descriptor = descriptor("hf")
        override fun search(query: SourceQuery): List<SourceHit> =
            listOf(hit("hf", "https://huggingface.co/acme/whisper-small", title = "whisper-small", description = "Speech recognition model"))
    }

    private val noCache = object : SearchCache {
        override fun get(key: String) = null
        override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

    private fun model(plan: GuideSearchPlan) = object : GuideModel {
        override val provider = "fake"
        override val fastModel = "fast"
        override val reasoningModel = "reasoning"
        override fun plan(request: String, path: AudiencePath?) = plan
        override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>) =
            GuideProposal(emptyList(), null, emptyList())
    }

    private fun stream(plan: GuideSearchPlan, maxConcurrent: Int = 1, question: String = "speech to text"): String {
        val guide = AskGuide(SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock), model(plan), clock)
        val bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(maxConcurrent).maxWaitDuration(Duration.ZERO).build())
        bulkheads.bulkhead("guide")
        val limits = GuideLimits(Resilience4jPolicies(CircuitBreakerRegistry.ofDefaults(), bulkheads, RetryRegistry.ofDefaults()), Duration.ofSeconds(90))
        val controller = GuideStreamController(guide, GuideDtoMapper(LlmSettings("fake", "", "fast", "reasoning")), limits,
            SignedInUser.ANONYMOUS, meters, Duration.ofSeconds(15))
        closers += { limits.destroy(); controller.destroy() }
        val mvc = MockMvcBuilders.standaloneSetup(controller).build()
        val result = mvc.perform(
            post("/api/v1/guide/stream").contentType(MediaType.APPLICATION_JSON).content("{\"request\":\"$question\"}"),
        ).andExpect(request().asyncStarted()).andReturn()
        result.getAsyncResult(10_000)
        mvc.perform(asyncDispatch(result))
        return result.response.contentAsString
    }

    private fun firstUseful(intent: String) = meters.find("helper2.guide.first_useful").tag("intent", intent).timer()

    @Test
    fun `the first useful event of an answered question is the retrieved results, timed once with its intent`() {
        val events = stream(GuideSearchPlan("speech to text", intent = GuideIntent.SUGGEST))

        assertTrue(events.contains("event:retrieved"), events)
        assertEquals(1, firstUseful("suggest")?.count())
        assertEquals(1, meters.find("helper2.guide.first_useful").timers().size)
    }

    @Test
    fun `a question asked back is timed at its clarify event`() {
        val events = stream(
            GuideSearchPlan("AI tools", intent = GuideIntent.NEWEST, clarify = Clarification("New tools for what?", listOf("coding", "agents"))),
            question = "new tools?",
        )

        assertTrue(events.contains("event:clarify"), events)
        assertTrue(!events.contains("event:retrieved"), events)
        assertEquals(1, firstUseful("newest")?.count())
    }

    @Test
    fun `a stream that ends before any useful event is not timed`() {
        val events = stream(GuideSearchPlan("speech to text"), maxConcurrent = 0)

        assertTrue(events.startsWith("event:error"), events)
        assertNull(meters.find("helper2.guide.first_useful").timer())
    }
}
