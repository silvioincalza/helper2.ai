package ai.helper2.guide.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.json.JsonMapper

/** The stored copy of an answer must read back equal, and any problem must be a miss, never an error. */
class JdbcGuideAnswerStoreTest {

    /** No database behind it: every call fails like an outage. */
    private val down = JdbcClient.create(DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none"))
    private val store = JdbcGuideAnswerStore(down, JsonMapper.builder().build(), "test")

    @Test
    fun `an answer reads back equal to the one that was stored`() {
        val original = GuideAnswerFixture.answer()
        assertTrue(original.recommendations.any { it.aiReasoned }, "fixture must be a cacheable answer")

        assertEquals(original, store.decode(store.encode(original)))
    }

    @Test
    fun `an entry of another version or a corrupt entry is a miss`() {
        val encoded = store.encode(GuideAnswerFixture.answer())

        val v = JdbcGuideAnswerStore.VERSION
        assertNull(store.decode(encoded.replaceFirst("\"v\":$v", "\"v\":${v + 1}")))
        assertNull(store.decode("{\"v\":$v,\"answer\":{\"status\":\"OK\"}}"))
        assertNull(store.decode("not json"))
    }

    @Test
    fun `an unavailable database is a miss`() {
        assertNull(store.get("a".repeat(64)))
    }
}
