package ai.helper2.platform.settings;

import ai.helper2.platform.config.RuntimeSettingsProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The runtime settings (ADR 0060): the store, the snapshot every module reads, its refresh on its
 * own thread. The daily purge of old changes is scheduled by the app that runs it.
 */
@Configuration(proxyBeanMethods = false)
class RuntimeSettingsConfiguration {

  private static final Logger log = LoggerFactory.getLogger(RuntimeSettingsConfiguration.class);

  /** How often the refresh thread looks at the clock; it reads the store only when due. */
  static final Duration TICK = Duration.ofSeconds(1);

  @Bean
  RuntimeSettingStore runtimeSettingStore(JdbcClient jdbc) {
    return new JdbcRuntimeSettingStore(jdbc);
  }

  /**
   * Read once at startup, so the first request already applies the stored overrides. A store that
   * cannot answer then leaves the defaults until the next refresh.
   */
  @Bean
  RuntimeSettings runtimeSettings(
      RuntimeSettingStore runtimeSettingStore, Clock clock, RuntimeSettingsProperties settings) {
    RuntimeSettings runtime = new RuntimeSettings(runtimeSettingStore, clock, settings.refresh());
    try {
      runtime.tick();
    } catch (RuntimeException e) {
      log.warn("runtime settings not read at startup: {}", e.getClass().getSimpleName());
    }
    return runtime;
  }

  @Bean(initMethod = "start", destroyMethod = "stop")
  Refresh runtimeSettingsRefresh(RuntimeSettings runtimeSettings) {
    return new Refresh(runtimeSettings);
  }

  /**
   * Its own thread, not @Scheduled: with virtual threads Spring runs every fixed-delay task on one
   * scheduler thread, and a long task there would hold the refresh back (ADR 0045, the indexer).
   */
  static final class Refresh {
    private final RuntimeSettings settings;
    private ScheduledExecutorService executor;

    Refresh(RuntimeSettings settings) {
      this.settings = settings;
    }

    void start() {
      executor =
          Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "runtime-settings");
            t.setDaemon(true);
            return t;
          });
      executor.scheduleWithFixedDelay(
          this::tickSafely, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);
    }

    void stop() {
      if (executor != null) {
        executor.shutdownNow();
      }
    }

    /** An exception escaping a scheduled task would cancel all later ticks. */
    private void tickSafely() {
      try {
        if (settings.tick()) {
          log.info("runtime settings read again: version {}", settings.version());
        }
      } catch (RuntimeException e) {
        log.warn("runtime settings refresh failed: {}", e.getClass().getSimpleName());
      }
    }
  }
}
