package ai.helper2.guide.internal

import ai.helper2.testing.InMemorySharedCache
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/** The shared copy of an answer must read back equal, per build, and any problem must be a miss, never an error. */
class SharedGuideAnswerStoreTest {

    private val cache = InMemorySharedCache("guide-answer")
    private val json = JsonMapper.builder().build()
    private fun store(build: String = "b1") = SharedGuideAnswerStore(cache, json, build)
    private val key = "a".repeat(64)

    @Test
    fun `an answer reads back equal, for its build only, kept for the ttl it was given`() {
        val original = GuideAnswerFixture.answer()
        assertTrue(original.recommendations.any { it.aiReasoned }, "fixture must be a cacheable answer")

        store().put(key, original, Duration.ofHours(23))

        assertEquals(original, store().get(key))
        assertNull(store("b2").get(key), "another release does not read it")
        assertEquals(Duration.ofHours(23), cache.ttls.values.single())
    }

    @Test
    fun `the stored key is a hash of the build and the guide's key`() {
        store().put(key, GuideAnswerFixture.answer(), Duration.ofHours(1))

        val stored = cache.values.keys.single()
        assertTrue(stored.matches(Regex("[0-9a-f]{64}")) && stored != key)
    }

    @Test
    fun `an entry of another version or a corrupt entry is a miss`() {
        val encoded = store().encode(GuideAnswerFixture.answer())

        val v = SharedGuideAnswerStore.VERSION
        assertNull(store().decode(encoded.replaceFirst("\"v\":$v", "\"v\":${v + 1}")))
        assertNull(store().decode("{\"v\":$v,\"answer\":{\"status\":\"OK\"}}"))
        assertNull(store().decode("not json"))
    }

    @Test
    fun `an unavailable cache is a miss`() {
        store().put(key, GuideAnswerFixture.answer(), Duration.ofHours(1))
        cache.down = true

        assertNull(store().get(key))
    }
}
