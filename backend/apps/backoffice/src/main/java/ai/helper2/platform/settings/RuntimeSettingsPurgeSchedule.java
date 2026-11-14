package ai.helper2.platform.settings;

import ai.helper2.platform.DailyJob;
import ai.helper2.platform.config.PlatformRetentionProperties;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RuntimeSettingsPurgeSchedule {

  private static final Logger log = LoggerFactory.getLogger(RuntimeSettingsPurgeSchedule.class);

  @Bean
  DailyJob runtimeSettingsRetention(
      RuntimeSettingStore store, Clock clock, PlatformRetentionProperties retention) {
    Duration keep = retention.runtimeSettings();
    return new DailyJob(
        "runtime-settings",
        () -> {
          int deleted = store.deleteBefore(clock.instant().minus(keep));
          log.info("runtime setting changes purged: {}", deleted);
        });
  }
}
