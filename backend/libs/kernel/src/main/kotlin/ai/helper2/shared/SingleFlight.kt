package ai.helper2.shared

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

class SingleFlight<T : Any> {
  private val inFlight = ConcurrentHashMap<String, Flight<T>>()

  sealed interface Outcome<T> {
    val value: T
  }

  class Led<T>(override val value: T) : Outcome<T>

  class Followed<T>(override val value: T) : Outcome<T>

  class Abandoned : RuntimeException("nobody waits for this work any more")

  @JvmOverloads
  fun follow(key: String, presence: Presence? = null): T? =
    inFlight[key]?.takeIf { it.admits(presence) }?.await(presence)

  @JvmOverloads
  fun run(
    key: String,
    presence: Presence? = null,
    beforeFollowing: () -> Unit,
    work: () -> T,
  ): Outcome<T> {
    val mine = Flight<T>(Thread.currentThread(), presence) { inFlight.remove(key, it) }
    takeOff(key, mine, presence)?.let {
      beforeFollowing()
      return Followed(it.await(presence))
    }
    try {
      return Led(mine.land(work))
    } finally {
      inFlight.remove(key, mine)
    }
  }

  private fun takeOff(key: String, mine: Flight<T>, presence: Presence?): Flight<T>? {
    while (true) {
      val running = inFlight.putIfAbsent(key, mine)
      if (running == null) {
        mine.watch(presence)
        return null
      }
      if (running.admits(presence)) return running
      inFlight.remove(key, running)
    }
  }

  private class Flight<T : Any>(
    private val leader: Thread,
    leaderPresence: Presence?,
    private val onAbandon: (Flight<T>) -> Unit,
  ) {
    private val result = CompletableFuture<T>()
    private var waiting = 0
    private var someoneCannotLeave = false
    private var running = true
    private var abandoned = false

    init {
      count(leaderPresence)
    }

    @Synchronized
    fun admits(presence: Presence?): Boolean {
      if (abandoned) return false
      count(presence)
      watch(presence)
      return true
    }

    fun watch(presence: Presence?) {
      presence?.whenGone(::left)
    }

    private fun count(presence: Presence?) {
      if (presence == null) someoneCannotLeave = true else waiting++
    }

    @Synchronized
    private fun left() {
      waiting--
      if (waiting > 0 || someoneCannotLeave || !running || abandoned) return
      abandoned = true
      onAbandon(this)
      leader.interrupt()
    }

    fun land(work: () -> T): T {
      val value =
        try {
          work()
        } catch (e: Throwable) {
          throw failed(if (endedAbandoned()) Abandoned() else e)
        }
      if (endedAbandoned()) throw failed(Abandoned())
      result.complete(value)
      return value
    }

    private fun failed(e: Throwable): Throwable {
      result.completeExceptionally(e)
      return e
    }

    @Synchronized
    private fun endedAbandoned(): Boolean {
      running = false
      if (abandoned) Thread.interrupted()
      return abandoned
    }

    fun await(presence: Presence?): T {
      val mine = result.copy()
      presence?.whenGone { mine.completeExceptionally(Abandoned()) }
      try {
        return mine.get()
      } catch (e: ExecutionException) {
        throw e.cause ?: e
      }
    }
  }
}
