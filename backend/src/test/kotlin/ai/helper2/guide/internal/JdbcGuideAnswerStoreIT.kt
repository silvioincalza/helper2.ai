package ai.helper2.guide.internal

import ai.helper2.TestcontainersConfiguration
import ai.helper2.guide.IndexedQuestion
import ai.helper2.guide.usecase.PurgeExpiredData
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper

/**
 * Guide answers and the similar-question index on the real PostgreSQL with pgvector (ADR 0041): read per build,
 * nearest question by cosine within a group, and the daily purge. Keys and groups are random, so tests share the
 * database safely; the clock of the purge is fixed in 2000, before any answer other tests store.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class JdbcGuideAnswerStoreIT {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper

    private val answer = GuideAnswerFixture.answer()
    private val later = Instant.now().plus(Duration.ofHours(24)).truncatedTo(ChronoUnit.MICROS)

    private fun store(build: String = "b1") = JdbcGuideAnswerStore(jdbc, json, build)
    private fun key() = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "")
    private fun group() = "test-" + UUID.randomUUID()

    /** A 1024-long unit vector at [angle] radians from the first axis, in the plane of the first two. */
    private fun vector(angle: Double) = FloatArray(1024).also { it[0] = cos(angle).toFloat(); it[1] = sin(angle).toFloat() }

    @Test
    fun `an answer reads back equal, under its key and for its build only`() {
        val key = key()
        store().put(key, answer, later)

        assertEquals(answer, store().get(key))
        assertNull(store("b2").get(key), "another release does not read it")
        assertNull(store().get(key()))
    }

    @Test
    fun `putting a key again replaces its answer and expiry`() {
        val key = key()
        store().put(key, answer, later)
        val newer = answer.copy(searchQuery = "newer")
        store().put(key, newer, later.plusSeconds(60))

        assertEquals("newer", store().get(key)!!.searchQuery)
        assertEquals(later.plusSeconds(60), jdbc.sql("SELECT expires_at FROM guide_answer WHERE answer_key = :k").param("k", key)
            .query(java.sql.Timestamp::class.java).single().toInstant())
    }

    @Test
    fun `the nearest live question of the group comes back with its cosine`() {
        val index = JdbcSimilarQuestionIndex(jdbc)
        val group = group()
        val near = key()
        val far = key()
        val expired = key()
        listOf(near, far, expired).forEach { store().put(it, answer, later) }
        index.add(group, IndexedQuestion(far, vector(0.5), later))
        index.add(group, IndexedQuestion(near, vector(0.2), later))
        index.add(group, IndexedQuestion(expired, vector(0.0), Instant.now().minusSeconds(1)))
        index.add(group(), IndexedQuestion(key().also { store().put(it, answer, later) }, vector(0.0), later))

        val found = assertNotNull(index.nearest(group, vector(0.0), Instant.now()))

        assertEquals(near, found.answerKey)
        assertEquals(cos(0.2), found.similarity, 1e-6)
        assertNull(index.nearest(group(), vector(0.0), Instant.now()))
    }

    @Test
    fun `a question is indexed only while its answer is stored, and goes with it`() {
        val index = JdbcSimilarQuestionIndex(jdbc)
        val group = group()
        index.add(group, IndexedQuestion(key(), vector(0.0), later))
        assertNull(index.nearest(group, vector(0.0), Instant.now()), "no answer row: not indexed")

        val key = key()
        store().put(key, answer, later)
        index.add(group, IndexedQuestion(key, vector(0.0), later))
        jdbc.sql("DELETE FROM guide_answer WHERE answer_key = :k").param("k", key).update()

        assertNull(index.nearest(group, vector(0.0), Instant.now()))
    }

    @Test
    fun `the daily purge deletes expired answers and questions, and keeps live ones`() {
        val now = Instant.parse("2000-07-01T00:00:00Z")
        val expired = key()
        val live = key()
        val group = group()
        store().put(expired, answer, now.minusSeconds(1))
        store().put(live, answer, now.plusSeconds(60))
        JdbcSimilarQuestionIndex(jdbc).add(group, IndexedQuestion(expired, vector(0.0), now.minusSeconds(1)))
        JdbcSimilarQuestionIndex(jdbc).add(group, IndexedQuestion(live, vector(0.0), now.minusSeconds(1)))

        val deleted = PurgeExpiredData(store(), Clock.fixed(now, ZoneOffset.UTC)).purge()

        assertEquals(1, deleted)
        assertNull(store().get(expired))
        assertNotNull(store().get(live))
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM guide_similar_question WHERE group_key = :g").param("g", group)
            .query(Long::class.java).single(), "the live answer's question expired on its own")
    }
}
