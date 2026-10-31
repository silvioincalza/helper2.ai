package ai.helper2.guide.internal

import ai.helper2.guide.Candidate
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.usecase.AskGuide
import ai.helper2.guide.ProposedPick
import ai.helper2.search.CachedHits
import ai.helper2.search.usecase.SearchResources
import ai.helper2.search.NOW
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import ai.helper2.platform.resilience.Resilience
import ai.helper2.testing.MovableClock
import ai.helper2.testing.TestPolicies
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.databind.json.JsonMapper

/** The Redis copy of an answer must read back equal, and any problem must be a miss, never an error. */
class RedisGuideAnswerStoreTest {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = MovableClock(NOW)

    @AfterTest
    fun shutdown() = executor.shutdownNow().let { }

    private class DownRedis : StringRedisTemplate() {
        var calls = 0
        override fun opsForValue(): ValueOperations<String, String> {
            calls++
            throw RedisConnectionFailureException("down")
        }
    }

    /** A realistic answer: two sources, a licence, dates, a repository URL, a prompt and constraint checks. */
    private fun answer(): GuideAnswer {
        val source = object : SearchSource {
            override val descriptor = descriptor("hf")
            override fun search(query: SourceQuery): List<SourceHit> = listOf(
                hit(
                    "hf", "https://huggingface.co/acme/whisper-small", title = "whisper-small", type = ResourceType.MODEL,
                    license = "MIT", published = "2026-01-02T00:00:00Z", repo = "https://github.com/acme/whisper",
                    description = "Speech recognition model you can self-host, released under the MIT License",
                ),
                hit(
                    "hf", "https://huggingface.co/acme/transcribe-prompt", title = "transcribe-prompt", rank = 1,
                    type = ResourceType.PROMPT, description = "Prompt to clean up a raw speech transcript into readable text",
                ),
            )
        }
        val noCache = object : SearchCache {
            override fun get(key: String) = null
            override fun put(key: String, value: CachedHits, ttl: Duration) {}
        }
        val model = object : GuideModel {
            override val provider = "fake"
            override val fastModel = "fast"
            override val reasoningModel = "reasoning"
            override fun plan(request: String, path: AudiencePath?) = GuideSearchPlan("speech recognition", listOf("MIT licence"))
            override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>): GuideProposal {
                val whisper = candidates.first { it.result.title == "whisper-small" }.key
                val prompt = candidates.first { it.result.title == "transcribe-prompt" }.key
                return GuideProposal(
                    listOf(ProposedPick(whisper, "Self-hostable.", "Speech recognition model you can self-host")),
                    "Clean up this transcript.", listOf(prompt),
                )
            }
        }
        val search = SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)
        return AskGuide(search, model, clock).ask("speech to text I can self-host, MIT licence", AudiencePath.BUILD_WITH_AI)
    }

    private val json = JsonMapper.builder().build()

    @Test
    fun `an answer reads back equal to the one that was stored`() {
        val store = RedisGuideAnswerStore(StringRedisTemplate(), json, Resilience.NONE, "test")
        val original = answer()
        assertTrue(original.recommendations.any { it.aiReasoned }, "fixture must be a cacheable answer")

        assertEquals(original, store.decode(store.encode(original)))
    }

    @Test
    fun `an entry of another version or a corrupt entry is a miss`() {
        val store = RedisGuideAnswerStore(StringRedisTemplate(), json, Resilience.NONE, "test")
        val encoded = store.encode(answer())

        val v = RedisGuideAnswerStore.VERSION
        assertNull(store.decode(encoded.replaceFirst("\"v\":$v", "\"v\":${v + 1}")))
        assertNull(store.decode("{\"v\":$v,\"answer\":{\"status\":\"OK\"}}"))
        assertNull(store.decode("not json"))
    }

    @Test
    fun `after a Redis failure the store is left alone for a while`() {
        val redis = DownRedis()
        val store = RedisGuideAnswerStore(redis, json, TestPolicies.redis(clock), "test")

        assertNull(store.get("k"))
        store.put("k", answer(), Duration.ofHours(24))
        assertNull(store.get("k"))
        assertEquals(1, redis.calls)

        clock.now = NOW.plusSeconds(31)
        assertNull(store.get("k"))
        assertEquals(2, redis.calls)
    }

    @Test
    fun `Redis sees only a hash of the question, under the build that answered it`() {
        val question = "speech to text i can self-host|-|fast|reasoning"
        val key = RedisGuideAnswerStore.redisKey("b86eda2f1a2b", question)

        assertTrue(key.startsWith("h2:v${RedisGuideAnswerStore.VERSION}:guide-answer:b86eda2f1a2b:"))
        assertTrue(!key.contains("speech"))
        assertEquals(key, RedisGuideAnswerStore.redisKey("b86eda2f1a2b", question))
        // A new build reads its own answers: a fix in how the guide reads a question shows at once.
        assertNotEquals(key, RedisGuideAnswerStore.redisKey("b6e6cd54aaaa", question))
        // A build id is never free text in the key.
        assertTrue(RedisGuideAnswerStore.redisKey("x y:*", question).startsWith("h2:v${RedisGuideAnswerStore.VERSION}:guide-answer:unknown:"))
    }
}
