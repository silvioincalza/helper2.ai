package ai.searchavo.scout.internal;

import ai.searchavo.platform.DailyJob;
import ai.searchavo.scout.RunHistoryRetention;
import ai.searchavo.scout.config.ScoutRetentionProperties;
import ai.searchavo.scout.usecase.PurgeExpiredData;
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
