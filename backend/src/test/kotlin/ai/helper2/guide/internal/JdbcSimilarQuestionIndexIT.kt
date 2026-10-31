package ai.helper2.guide.internal

import ai.helper2.TestcontainersConfiguration
import ai.helper2.guide.IndexedQuestion
import ai.helper2.guide.usecase.PurgeExpiredData
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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

/**
 * The similar-question index on the real PostgreSQL with pgvector (ADR 0041): nearest live question by cosine within a
 * group, one row per answer key, and the daily purge. Keys and groups are random, so tests share the database safely;
 * the clock of the purge is fixed in 2000, before any question other tests index.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class JdbcSimilarQuestionIndexIT {

    @Autowired lateinit var jdbc: JdbcClient

    private val later = Instant.now().plus(Duration.ofHours(24))

    private fun index() = JdbcSimilarQuestionIndex(jdbc)
    private fun key() = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "")
    private fun group() = "test-" + UUID.randomUUID()

    /** A 1024-long unit vector at [angle] radians from the first axis, in the plane of the first two. */
    private fun vector(angle: Double) = FloatArray(1024).also { it[0] = cos(angle).toFloat(); it[1] = sin(angle).toFloat() }

    @Test
    fun `the nearest live question of the group comes back with its cosine`() {
        val group = group()
        val near = key()
        index().add(group, IndexedQuestion(key(), vector(0.5), later))
        index().add(group, IndexedQuestion(near, vector(0.2), later))
        index().add(group, IndexedQuestion(key(), vector(0.0), Instant.now().minusSeconds(1)))
        index().add(group(), IndexedQuestion(key(), vector(0.0), later))

        val found = assertNotNull(index().nearest(group, vector(0.0), Instant.now()))

        assertEquals(near, found.answerKey)
        assertEquals(cos(0.2), found.similarity, 1e-6)
        assertNull(index().nearest(group(), vector(0.0), Instant.now()))
    }

    @Test
    fun `indexing an answer key again replaces its question`() {
        val group = group()
        val key = key()
        index().add(group, IndexedQuestion(key, vector(1.0), later))
        index().add(group, IndexedQuestion(key, vector(0.0), later))

        assertEquals(1.0, index().nearest(group, vector(0.0), Instant.now())!!.similarity, 1e-6)
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM guide_similar_question WHERE answer_key = :k").param("k", key)
            .query(Long::class.java).single())
    }

    @Test
    fun `the daily purge deletes expired questions and keeps live ones`() {
        val now = Instant.parse("2000-07-01T00:00:00Z")
        val group = group()
        index().add(group, IndexedQuestion(key(), vector(0.0), now.minusSeconds(1)))
        index().add(group, IndexedQuestion(key(), vector(0.0), now.plusSeconds(60)))

        val deleted = PurgeExpiredData(index(), Clock.fixed(now, ZoneOffset.UTC)).purge()

        assertEquals(1, deleted)
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM guide_similar_question WHERE group_key = :g").param("g", group)
            .query(Long::class.java).single())
    }
}
