package ai.helper2.platform;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;

public final class ClusterLocks implements AutoCloseable {

  private final ExtensibleLockProvider provider;
  private final ScheduledExecutorService renewals;

  public ClusterLocks(ExtensibleLockProvider provider) {
    this.provider = provider;
    this.renewals =
        Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "cluster-lock-renewal");
          t.setDaemon(true);
          return t;
        });
  }

  public ClusterLock keptAlive(String name, Duration lease) {
    return new ClusterLock(
        new DefaultLockingTaskExecutor(new KeepAliveLockProvider(provider, renewals)),
        name,
        lease,
        Duration.ZERO);
  }

  @Override
  public void close() {
    renewals.shutdownNow();
  }
}
