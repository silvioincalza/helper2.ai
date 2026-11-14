package ai.helper2.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.jspecify.annotations.Nullable;

public final class ClusterLock {

  private final LockingTaskExecutor executor;
  private final String name;
  private final Duration atMostFor;
  private final Duration atLeastFor;

  public ClusterLock(
      LockingTaskExecutor executor, String name, Duration atMostFor, Duration atLeastFor) {
    this.executor = executor;
    this.name = name;
    this.atMostFor = atMostFor;
    this.atLeastFor = atLeastFor;
    configuration();
  }

  public String name() {
    return name;
  }

  public Duration atMostFor() {
    return atMostFor;
  }

  public Duration atLeastFor() {
    return atLeastFor;
  }

  public <T> @Nullable T tryCall(Supplier<T> task) {
    TaskResult<T> result;
    try {
      result = executor.executeWithLock(task::get, configuration());
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
    return result.wasExecuted() ? result.getResult() : null;
  }

  public boolean tryRun(Runnable task) {
    return tryCall(() -> {
      task.run();
      return Boolean.TRUE;
    })
        != null;
  }

  private LockConfiguration configuration() {
    return new LockConfiguration(Instant.now(), name, atMostFor, atLeastFor);
  }
}
