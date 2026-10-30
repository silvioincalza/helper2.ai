package ai.helper2.platform;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;

/**
 * Cluster locks for tasks of unknown length (a Scout run: minutes to hours). Such a lock is a short lease that
 * ShedLock's {@code KeepAliveLockProvider} extends by another lease every half lease while the task lives. A replica
 * that dies mid-task stops extending it, so the lock frees at most one lease later, however long the task would have
 * run. One daemon thread renews every lock of this replica.
 */
public final class ClusterLocks implements AutoCloseable {

    private final ExtensibleLockProvider provider;
    private final ScheduledExecutorService renewals;

    public ClusterLocks(ExtensibleLockProvider provider) {
        this.provider = provider;
        this.renewals = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-lock-renewal");
            t.setDaemon(true);
            return t;
        });
    }

    /** A lock named {@code name}, held for {@code lease} (at least 30 s) at a time and renewed while its task runs. */
    public ClusterLock keptAlive(String name, Duration lease) {
        return new ClusterLock(new DefaultLockingTaskExecutor(new KeepAliveLockProvider(provider, renewals)), name, lease, Duration.ZERO);
    }

    @Override
    public void close() {
        renewals.shutdownNow();
    }
}
