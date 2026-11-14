package ai.helper2.shared

import java.util.concurrent.CompletableFuture

class Presence {
  private val gone = CompletableFuture<Unit>()

  fun leave() {
    gone.complete(Unit)
  }

  fun whenGone(action: () -> Unit) {
    gone.thenRun(action)
  }
}
