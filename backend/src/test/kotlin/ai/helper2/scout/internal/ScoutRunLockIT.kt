package ai.helper2.scout.internal

import ai.helper2.TestcontainersConfiguration
import ai.helper2.platform.ClusterLock
import ai.helper2.scout.CatalogIndex
import ai.helper2.scout.CollectedBatch
import ai.helper2.scout.Collector
import ai.helper2.scout.RunSummary
import ai.helper2.scout.ScheduledRun
import ai.helper2.scout.ScheduledRunStore
import ai.helper2.scout.ScoutCursor
import ai.helper2.scout.usecase.RunScout
import ai.helper2.scout.ScoutStore
import ai.helper2.search.descriptor
import ai.helper2.testing.MovableClock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Two replicas of the app, simulated by two lock providers with their own `locked_by`, on the real Postgres. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ScoutRunLockIT {

    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var scoutRunLock: ClusterLock
    @Autowired lateinit var schedule: ScheduledRunStore

    private fun replica(pod: String) = DefaultLockingTaskExecutor(JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(JdbcTemplate(dataSource))
            .withLockedByValue(pod).usingDbTime().build()))

    private class NoStore : ScoutStore {
        override fun cursor(sourceId: String): ScoutCursor? = null
        override fun saveCursor(sourceId: String, cursor: ScoutCursor) {}
        override fun save(run: RunSummary) {}
        override fun recent(limit: Int) = emptyList<RunSummary>()
    }

    private fun runner(pod: String, lockName: String, collector: Collector, clock: Clock = Clock.systemUTC()) = RunScout(
        listOf(collector), NoStore(), CatalogIndex { _, _ -> emptyList() }, null, clock,
        ScoutConfiguration.ClusterRunLock(ClusterLock(replica(pod), lockName, Duration.ofMinutes(1), Duration.ZERO)))

    @Test
    fun `two replicas starting a run at the same time run it once`() {
        val lockName = "scout-run-it-" + UUID.randomUUID()
        val collections = AtomicInteger()
        val release = CountDownLatch(1)
        val collector = object : Collector {
            override val descriptor = descriptor("mcp")
            override fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int): CollectedBatch {
                collections.incrementAndGet()
                release.await(10, TimeUnit.SECONDS)
                return CollectedBatch(emptyList(), null, "cp")
            }
        }
        val replicas = listOf(runner("pod-a", lockName, collector), runner("pod-b", lockName, collector))
        val start = CyclicBarrier(2)
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val attempts = replicas.map { r ->
                CompletableFuture.supplyAsync({ start.await(); r.tryRunAll() }, threads)
            }
            // The run that got the lock waits in its collector, so the first attempt to end is the refused one.
            assertNull(CompletableFuture.anyOf(*attempts.toTypedArray()).get(10, TimeUnit.SECONDS))
            release.countDown()
            val results = attempts.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it != null })
            assertEquals(1, collections.get())
        }

        // Released when the run ends: the other replica can run next.
        assertNotNull(replicas[1].tryRunAll())
        assertEquals(2, collections.get())
    }

    /** Counts collections; while [hold] is closed, a collection waits for it (a run in progress). */
    private class CountingCollector(val hold: CountDownLatch = CountDownLatch(0)) : Collector {
        val collections = AtomicInteger()
        override val descriptor = descriptor("mcp")
        override fun collect(checkpoint: String?, resumeCursor: String?, maxItems: Int): CollectedBatch {
            collections.incrementAndGet()
            hold.await(10, TimeUnit.SECONDS)
            return CollectedBatch(emptyList(), null, "cp")
        }
    }

    private fun noScheduledRunYet() {
        JdbcTemplate(dataSource).update("DELETE FROM scout_schedule")
    }

    @Test
    fun `the first tick with no history runs, and two replicas run once per period`() {
        noScheduledRunYet()
        val lockName = "scout-run-it-" + UUID.randomUUID()
        val clock = MovableClock(Instant.parse("2026-10-27T03:00:00Z"))
        val day = Duration.ofDays(1)
        val release = CountDownLatch(1)
        val collector = CountingCollector(release)
        val replicas = listOf(runner("pod-a", lockName, collector, clock), runner("pod-b", lockName, collector, clock))

        // Both replicas tick at once: one runs, the other finds the run in progress.
        val start = CyclicBarrier(2)
        val outcomes = Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val ticks = replicas.map { r -> CompletableFuture.supplyAsync({ start.await(); r.tryRunScheduled(day, schedule) }, threads) }
            assertEquals(ScheduledRun.BUSY, CompletableFuture.anyOf(*ticks.toTypedArray()).get(10, TimeUnit.SECONDS))
            release.countDown()
            ticks.map { it.get(10, TimeUnit.SECONDS) }
        }
        assertEquals(setOf(ScheduledRun.BUSY, ScheduledRun.RAN), outcomes.toSet())
        assertEquals(1, collector.collections.get())
        assertEquals(clock.now, schedule.lastScheduledRunEnd())

        // Later ticks of the same period, on either replica, do nothing.
        clock.now = clock.now.plus(Duration.ofHours(23))
        replicas.forEach { assertEquals(ScheduledRun.NOT_DUE, it.tryRunScheduled(day, schedule)) }
        assertEquals(1, collector.collections.get())

        // A period after the end, the first replica to tick runs it, and only that one.
        clock.now = clock.now.plus(Duration.ofHours(1))
        assertEquals(ScheduledRun.RAN, replicas[1].tryRunScheduled(day, schedule))
        assertEquals(ScheduledRun.NOT_DUE, replicas[0].tryRunScheduled(day, schedule))
        assertEquals(2, collector.collections.get())
    }

    @Test
    fun `a tick that meets an admin run is retried at the next tick, and the admin run does not count`() {
        noScheduledRunYet()
        val lockName = "scout-run-it-" + UUID.randomUUID()
        val clock = MovableClock(Instant.parse("2026-10-28T03:00:00Z"))
        val lastScheduled = clock.now.minus(Duration.ofHours(25))
        schedule.scheduledRunEnded(lastScheduled)
        val release = CountDownLatch(1)
        val adminCollector = CountingCollector(release)
        val admin = runner("pod-a", lockName, adminCollector, clock)
        val scheduledCollector = CountingCollector()
        val scheduled = runner("pod-b", lockName, scheduledCollector, clock)

        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val adminRun = CompletableFuture.supplyAsync({ admin.tryRunAll() }, threads)
            while (adminCollector.collections.get() == 0) Thread.sleep(10)

            assertEquals(ScheduledRun.BUSY, scheduled.tryRunScheduled(Duration.ofDays(1), schedule))

            release.countDown()
            assertNotNull(adminRun.get(10, TimeUnit.SECONDS))
        }
        assertEquals(lastScheduled, schedule.lastScheduledRunEnd())
        assertEquals(0, scheduledCollector.collections.get())

        clock.now = clock.now.plus(Duration.ofHours(1))
        assertEquals(ScheduledRun.RAN, scheduled.tryRunScheduled(Duration.ofDays(1), schedule))
        assertEquals(1, scheduledCollector.collections.get())
    }

    @Test
    fun `the run lock outlasts the collectors plus every enrichment of a run timing out`() {
        // Test defaults: 20 enrichments per run, 40 s model timeout, no SDK retry.
        assertEquals("scout-run", scoutRunLock.name())
        assertEquals(Duration.ofHours(1).plus(Duration.ofSeconds(20L * 40)), scoutRunLock.atMostFor())
        assertEquals(Duration.ZERO, scoutRunLock.atLeastFor())
    }
}
