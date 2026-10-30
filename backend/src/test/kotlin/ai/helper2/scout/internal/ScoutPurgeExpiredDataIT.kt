package ai.helper2.scout.internal

import ai.helper2.TestcontainersConfiguration
import ai.helper2.scout.RunHistoryRetention
import ai.helper2.scout.usecase.PurgeExpiredData
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** The Scout's retention rule on the real schema; the clock is fixed in 2000, before any run other tests record. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ScoutPurgeExpiredDataIT {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var history: RunHistoryRetention

    private val now = Instant.parse("2000-07-01T00:00:00Z")

    private fun run(status: String, daysAgo: Long): UUID = UUID.randomUUID().also {
        jdbc.sql("INSERT INTO scout_run (id, source_id, status, started_at) VALUES (:id, 'mcp-registry', :status, :at)")
            .param("id", it).param("status", status).param("at", Timestamp.from(now.minus(Duration.ofDays(daysAgo)))).update()
    }

    private fun exists(id: UUID) =
        jdbc.sql("SELECT count(*) FROM scout_run WHERE id = :id").param("id", id).query(Long::class.java).single() == 1L

    @Test
    fun `run records older than ninety days are deleted, a running one stays`() {
        val old = run("SUCCEEDED", 91)
        val recent = run("FAILED", 89)
        val running = run("RUNNING", 400)

        assertEquals(1, PurgeExpiredData(history, Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(90)).purge())

        assertEquals(false, exists(old))
        assertEquals(true, exists(recent))
        assertEquals(true, exists(running))
        jdbc.sql("DELETE FROM scout_run WHERE id = :id").param("id", running).update()
    }
}
