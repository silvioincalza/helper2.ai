package ai.helper2.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.jspecify.annotations.Nullable;

/**
 * One named lock shared by every replica (see ClusterLockConfiguration). A task runs only while its replica holds
 * the lock; elsewhere it is skipped, never queued.
 *
 * <p>{@code atMostFor} must exceed the longest run: after it the lock expires even if the task is still running
 * (that is what frees the lock when a pod dies mid-task). A lock from {@link ClusterLocks#keptAlive} is extended
 * while its task runs, so there {@code atMostFor} is only the lease that a dead pod leaves behind.
 * {@code atLeastFor} keeps the lock after a short task ends, so replicas whose schedules fire a little later skip
 * that round instead of repeating it.
 */
public final class ClusterLock {

    private final LockingTaskExecutor executor;
    private final String name;
    private final Duration atMostFor;
    private final Duration atLeastFor;

    public ClusterLock(LockingTaskExecutor executor, String name, Duration atMostFor, Duration atLeastFor) {
        this.executor = executor;
        this.name = name;
        this.atMostFor = atMostFor;
        this.atLeastFor = atLeastFor;
        // Fails at startup, not at the first run, when the durations do not fit together.
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

    /** The task's result (never null), or null without running it while the lock is held elsewhere. */
    public <T> @Nullable T tryCall(Supplier<T> task) {
        TaskResult<T> result;
        try {
            result = executor.executeWithLock(task::get, configuration());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            // task::get throws no checked exception: unreachable.
            throw new IllegalStateException(t);
        }
        return result.wasExecuted() ? result.getResult() : null;
    }

    /** false, without running it, while the lock is held elsewhere. */
    public boolean tryRun(Runnable task) {
        return tryCall(() -> {
            task.run();
            return Boolean.TRUE;
        }) != null;
    }

    private LockConfiguration configuration() {
        return new LockConfiguration(Instant.now(), name, atMostFor, atLeastFor);
    }
}
