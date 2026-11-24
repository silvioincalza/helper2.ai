package ai.searchavo.guide.internal;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

final class JudgeBulkhead implements Executor {

  private final Semaphore slots;
  private final Executor runner;
  private final Runnable dropped;

  JudgeBulkhead(int slots, Executor runner, Runnable dropped) {
    this.slots = new Semaphore(slots);
    this.runner = runner;
    this.dropped = dropped;
  }

  @Override
  public void execute(Runnable task) {
    if (!slots.tryAcquire()) {
      dropped.run();
      return;
    }
    try {
      runner.execute(() -> runAndRelease(task));
    } catch (RuntimeException e) {
      slots.release();
      throw e;
    }
  }

  private void runAndRelease(Runnable task) {
    try {
      task.run();
    } finally {
      slots.release();
    }
  }
}
