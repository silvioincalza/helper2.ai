package ai.helper2.platform.cache;

import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purges the shared caches once a day (helper2.retention.cron, UTC), on one replica at a time. */
@Component
class SharedCacheRetentionSchedule {

  private static final Logger log = LoggerFactory.getLogger(SharedCacheRetentionSchedule.class);

  private final SharedCacheRetention retention;
  private final ClusterLock lock;

  SharedCacheRetentionSchedule(JdbcClient jdbc, ClusterLocks locks) {
    this.retention = new SharedCacheRetention(jdbc);
    this.lock = locks.keptAlive("retention-shared-cache", Duration.ofMinutes(1));
  }

  @Scheduled(cron = "${helper2.retention.cron}", zone = "UTC")
  void run() {
    try {
      lock.tryRun(retention::purge);
    } catch (RuntimeException e) {
      log.warn("shared cache retention failed: {}", e.getClass().getSimpleName());
    }
  }
}
