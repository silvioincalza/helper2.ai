package ai.helper2.platform.settings;

import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.platform.config.PlatformRetentionProperties;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes runtime setting changes older than the retention, except each setting's newest (ADR 0035,
 * 0007), once a day (helper2.retention.cron, UTC), on one replica at a time.
 */
@Component
class RuntimeSettingsPurgeSchedule {

  private static final Logger log = LoggerFactory.getLogger(RuntimeSettingsPurgeSchedule.class);

  private final RuntimeSettingStore store;
  private final Clock clock;
  private final Duration keep;
  private final ClusterLock lock;

  RuntimeSettingsPurgeSchedule(
      RuntimeSettingStore store,
      Clock clock,
      ClusterLocks locks,
      PlatformRetentionProperties retention) {
    this.store = store;
    this.clock = clock;
    this.keep = retention.runtimeSettings();
    this.lock = locks.keptAlive("retention-runtime-settings", Duration.ofMinutes(1));
  }

  @Scheduled(cron = "${helper2.retention.cron}", zone = "UTC")
  void run() {
    try {
      lock.tryRun(() -> {
        int deleted = store.deleteBefore(clock.instant().minus(keep));
        log.info("runtime setting changes purged: {}", deleted);
      });
    } catch (RuntimeException e) {
      log.warn("runtime settings retention failed: {}", e.getClass().getSimpleName());
    }
  }
}
