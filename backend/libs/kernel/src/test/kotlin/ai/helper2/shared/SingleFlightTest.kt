package ai.helper2.shared

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SingleFlightTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val flights = SingleFlight<String>()

  @AfterTest
  fun shutdown() {
    executor.shutdownNow()
  }

  private class SlowWork {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    @Volatile var interrupted = false

    fun run(): String {
      started.countDown()
      try {
        release.await()
      } catch (e: InterruptedException) {
        interrupted = true
        throw e
      }
      return "answer"
    }
  }

  private fun lead(work: SlowWork, presence: Presence?) =
    CompletableFuture.supplyAsync(
      { flights.run("key", presence, beforeFollowing = {}) { work.run() } },
      executor,
    )

  private fun follow(presence: Presence?) =
    CompletableFuture.supplyAsync(
      { flights.run("key", presence, beforeFollowing = {}) { "second answer" } },
      executor,
    )

  private fun <T> CompletableFuture<T>.failure(): Throwable =
    assertFailsWith<Exception> { get(5, TimeUnit.SECONDS) }.cause!!

  @Test
  fun `work whose only asker leaves is interrupted and ends as abandoned`() {
    val work = SlowWork()
    val asker = Presence()
    val led = lead(work, asker)
    assertTrue(work.started.await(5, TimeUnit.SECONDS))

    asker.leave()

    assertIs<SingleFlight.Abandoned>(led.failure())
    assertTrue(work.interrupted)
  }

  @Test
  fun `work goes on while a follower still waits, and the follower gets its value`() {
    val work = SlowWork()
    val leader = Presence()
    val led = lead(work, leader)
    assertTrue(work.started.await(5, TimeUnit.SECONDS))
    val beforeFollowing = CountDownLatch(1)
    val followed =
      CompletableFuture.supplyAsync(
        {
          flights.run("key", Presence(), beforeFollowing = beforeFollowing::countDown) {
            "second answer"
          }
        },
        executor,
      )
    assertTrue(beforeFollowing.await(5, TimeUnit.SECONDS))

    leader.leave()
    work.release.countDown()

    assertEquals("answer", followed.get(5, TimeUnit.SECONDS).value)
    assertIs<SingleFlight.Followed<String>>(followed.get())
    assertEquals("answer", led.get(5, TimeUnit.SECONDS).value)
    assertFalse(work.interrupted)
  }

  @Test
  fun `a follower who leaves stops waiting, and the work goes on for its leader`() {
    val work = SlowWork()
    val led = lead(work, Presence())
    assertTrue(work.started.await(5, TimeUnit.SECONDS))
    val follower = Presence()
    val beforeFollowing = CountDownLatch(1)
    val followed =
      CompletableFuture.supplyAsync(
        { flights.run("key", follower, beforeFollowing = beforeFollowing::countDown) { "x" } },
        executor,
      )
    assertTrue(beforeFollowing.await(5, TimeUnit.SECONDS))

    follower.leave()

    assertIs<SingleFlight.Abandoned>(followed.failure())
    work.release.countDown()
    assertEquals("answer", led.get(5, TimeUnit.SECONDS).value)
  }

  @Test
  fun `work ends as abandoned once the leader and every follower have left`() {
    val work = SlowWork()
    val leader = Presence()
    val led = lead(work, leader)
    assertTrue(work.started.await(5, TimeUnit.SECONDS))
    val follower = Presence()
    val beforeFollowing = CountDownLatch(1)
    val followed =
      CompletableFuture.supplyAsync(
        { flights.run("key", follower, beforeFollowing = beforeFollowing::countDown) { "x" } },
        executor,
      )
    assertTrue(beforeFollowing.await(5, TimeUnit.SECONDS))

    leader.leave()
    assertFalse(led.isDone)
    follower.leave()

    assertIs<SingleFlight.Abandoned>(led.failure())
    assertIs<SingleFlight.Abandoned>(followed.failure())
  }

  @Test
  fun `work with an asker who cannot leave is never abandoned`() {
    val work = SlowWork()
    val led = lead(work, null)
    assertTrue(work.started.await(5, TimeUnit.SECONDS))
    val follower = Presence()
    val followed = CompletableFuture.supplyAsync({ flights.follow("key", follower) }, executor)

    follower.leave()
    assertIs<SingleFlight.Abandoned>(followed.failure())
    work.release.countDown()

    assertEquals("answer", led.get(5, TimeUnit.SECONDS).value)
  }

  @Test
  fun `the same work asked after an abandon starts afresh`() {
    val work = SlowWork()
    val asker = Presence()
    val led = lead(work, asker)
    assertTrue(work.started.await(5, TimeUnit.SECONDS))
    asker.leave()
    assertIs<SingleFlight.Abandoned>(led.failure())

    val again = follow(Presence()).get(5, TimeUnit.SECONDS)

    assertIs<SingleFlight.Led<String>>(again)
    assertEquals("second answer", again.value)
  }

  @Test
  fun `an abandon leaves the leader's thread uninterrupted`() {
    val work = SlowWork()
    val asker = Presence()
    val interruptedAfter = CompletableFuture<Boolean>()
    executor.submit {
      try {
        flights.run("key", asker, beforeFollowing = {}) { work.run() }
      } catch (_: SingleFlight.Abandoned) {
        interruptedAfter.complete(Thread.currentThread().isInterrupted)
      }
    }
    assertTrue(work.started.await(5, TimeUnit.SECONDS))

    asker.leave()

    assertFalse(interruptedAfter.get(5, TimeUnit.SECONDS))
  }
}
