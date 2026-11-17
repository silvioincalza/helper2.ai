package ai.helper2.scout.internal

import ai.helper2.TestcontainersConfiguration
import ai.helper2.scout.LastSuccesses
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LastSuccessGaugesIT {

  @Autowired lateinit var jdbc: JdbcClient
  @Autowired lateinit var lastSuccesses: LastSuccesses
  @Autowired lateinit var meters: MeterRegistry

  private val succeeded = Instant.parse("2099-11-15T03:07:49Z")
  private val runs = mutableListOf<UUID>()

  @AfterEach
  fun clean() {
    runs.forEach { jdbc.sql("DELETE FROM scout_run WHERE id = :id").param("id", it).update() }
  }

  private fun run(status: String, finishedAt: Instant) {
    val id = UUID.randomUUID().also { runs += it }
    jdbc
      .sql(
        """
        INSERT INTO scout_run (id, source_id, status, started_at, finished_at)
        VALUES (:id, 'mcp-registry', :status, :at, :at)
        """
      )
      .param("id", id)
      .param("status", status)
      .param("at", Timestamp.from(finishedAt))
      .update()
  }

  private fun freshPod(): MeterRegistry =
    SimpleMeterRegistry().also {
      LastSuccessGauges(
          listOf("mcp-registry"),
          lastSuccesses,
          Clock.systemUTC(),
          Duration.ofSeconds(30),
        )
        .bindTo(it)
    }

  private fun MeterRegistry.lastSuccess() =
    get("helper2.scout.last.success").tag("source", "mcp-registry").gauge().value()

  @Test
  fun `the gauge reads the newest succeeded run, so a restarted pod reports the same value`() {
    run("SUCCEEDED", succeeded.minus(Duration.ofDays(1)))
    run("SUCCEEDED", succeeded)
    run("FAILED", succeeded.plus(Duration.ofHours(1)))
    run("SKIPPED", succeeded.plus(Duration.ofHours(2)))

    val before = freshPod().lastSuccess()
    val afterRestart = freshPod().lastSuccess()

    assertEquals(succeeded.epochSecond.toDouble(), before)
    assertEquals(before, afterRestart)
  }

  @Test
  fun `the application registers the gauge for every collector at start-up`() {
    assertNotNull(meters.find("helper2.scout.last.success").tag("source", "mcp-registry").gauge())
  }
}
