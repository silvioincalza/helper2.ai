package ai.helper2.guide.web

import ai.helper2.guide.Candidate
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
import ai.helper2.testing.MdcTraceIds
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guide's bounds: concurrent questions (the `guide` bulkhead) and a deadline per question. The full pipeline
 * runs in GuideApiIT.
 */
class GuideLimitsTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val limits = mutableListOf<GuideLimits>()
    private val streams = mutableListOf<GuideStreamController>()

    @AfterTest
    fun shutdown() {
        executor.shutdownNow()
        limits.forEach { it.destroy() }
        streams.forEach { it.destroy() }
    }

    private val source = object : SearchSource {
        override val descriptor = descriptor("hf")
        override fun search(query: SourceQuery): List<SourceHit> =
            listOf(
                hit(
                    "hf",
                    "https://huggingface.co/acme/whisper-small",
                    title = "whisper-small",
                    description = "Speech recognition model"
                )
            )
    }

    private val noCache = object : SearchCache {
        override fun get(key: String) = null
        override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

    /** A model that never answers in time: plan blocks until the worker is interrupted. */
    private val stuck = object : GuideModel {
        override val provider = "fake"
        override val fastModel = "fast"
        override val reasoningModel = "reasoning"
        override fun plan(request: String, path: AudiencePath?): GuideSearchPlan {
            Thread.sleep(Duration.ofSeconds(30))
            return GuideSearchPlan("speech")
        }

        override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>) =
            GuideProposal(emptyList(), null, emptyList())
    }

    private fun mvc(maxConcurrent: Int, deadline: Duration): MockMvc {
        val guide =
            AskGuide(SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock), stuck, clock)
        val mapper = GuideDtoMapper(LlmSettings("fake", "", "fast", "reasoning"))
        val bulkheads =
            BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(maxConcurrent).maxWaitDuration(Duration.ZERO).build())
        bulkheads.bulkhead("guide")
        val policies = Resilience4jPolicies(CircuitBreakerRegistry.ofDefaults(), bulkheads, RetryRegistry.ofDefaults())
        val shared = GuideLimits(policies, deadline).also { limits += it }
        return MockMvcBuilders.standaloneSetup(
            GuideStreamController(
                guide,
                mapper,
                shared,
                SignedInUser.ANONYMOUS,
                SimpleMeterRegistry(),
                Duration.ofMillis(50)
            ).also { streams += it },
        ).build()
    }

    private fun stream(mvc: MockMvc): String {
        val result = mvc.perform(
            post("/api/v1/guide/stream").contentType(MediaType.APPLICATION_JSON).content("{\"request\":\"speech to text\"}"),
        ).andExpect(request().asyncStarted()).andReturn()
        result.getAsyncResult(10_000)
        mvc.perform(asyncDispatch(result))
        return result.response.contentAsString
    }

    @Test
    fun `when every slot is taken the client is told at once that the guide is busy`() {
        val events = stream(mvc(maxConcurrent = 0, deadline = Duration.ofSeconds(90)))

        assertTrue(events.startsWith("event:error"), events)
        assertTrue(events.contains("\"status\":503"), events)
    }

    @Test
    fun `a question past its deadline is cancelled and the stream still ends with an error event`() {
        val started = System.nanoTime()

        val events = stream(mvc(maxConcurrent = 1, deadline = Duration.ofMillis(300)))

        assertTrue(events.contains("event:error"), events)
        assertTrue(events.contains("\"status\":504"), events)
        assertTrue(events.indexOf(":keep-alive") in 0..<events.indexOf("event:error"), events)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(10))
    }

    @Test
    fun `a second question while every slot is taken is told at once that the guide is busy`() {
        val mvc = mvc(maxConcurrent = 1, deadline = Duration.ofMillis(500))
        val running = mvc.perform(
            post("/api/v1/guide/stream").contentType(MediaType.APPLICATION_JSON).content("{\"request\":\"speech to text\"}"),
        ).andReturn()

        val events = stream(mvc)

        assertTrue(events.startsWith("event:error"), events)
        assertTrue(events.contains("\"status\":503"), events)
        running.getAsyncResult(10_000)
    }

    @Test
    fun `a cancelled question gives its slot back`() {
        val mvc = mvc(maxConcurrent = 1, deadline = Duration.ofMillis(200))
        stream(mvc)
        Thread.sleep(200)

        val events = stream(mvc)

        assertTrue(events.contains("\"status\":504"), events)
    }

    @Test
    fun `the deadline callback logs with the trace id of the question's request`() {
        val bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build())
        bulkheads.bulkhead("guide")
        val shared = GuideLimits(
            Resilience4jPolicies(CircuitBreakerRegistry.ofDefaults(), bulkheads, RetryRegistry.ofDefaults()),
            Duration.ofMillis(100)
        ).also { limits += it }
        val request = Observation.start("http.server.requests", MdcTraceIds.registry())
        val seen = CompletableFuture<String?>()

        request.openScope().use {
            shared.tryStart({ Thread.sleep(Duration.ofSeconds(30)) }, { seen.complete(MDC.get(MdcTraceIds.KEY)) })
        }
        request.stop()

        assertEquals(MdcTraceIds.traceId(request), seen.get(5, TimeUnit.SECONDS))
    }
}
