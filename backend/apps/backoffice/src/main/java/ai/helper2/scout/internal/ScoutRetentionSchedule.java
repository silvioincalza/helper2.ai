package ai.helper2.scout.internal;

import ai.helper2.platform.DailyJob;
import ai.helper2.scout.RunHistoryRetention;
import ai.helper2.scout.config.ScoutRetentionProperties;
import ai.helper2.scout.usecase.PurgeExpiredData;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ScoutRetentionSchedule {

  @Bean("scoutPurgeExpiredData")
  PurgeExpiredData scoutPurgeExpiredData(
      RunHistoryRetention history, Clock clock, ScoutRetentionProperties periods) {
    return new PurgeExpiredData(history, clock, periods.scoutRuns());
  }

  @Bean
  DailyJob scoutRetention(PurgeExpiredData scoutPurgeExpiredData) {
    return new DailyJob("scout", scoutPurgeExpiredData::purge);
  }
}
