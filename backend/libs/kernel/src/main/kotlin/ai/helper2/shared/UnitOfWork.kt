package ai.helper2.shared

import java.util.function.Supplier

interface UnitOfWork {
  fun <T : Any> run(work: Supplier<T>): T

  fun runWithoutResult(work: Runnable) {
    run(Supplier { work.run() })
  }
}
