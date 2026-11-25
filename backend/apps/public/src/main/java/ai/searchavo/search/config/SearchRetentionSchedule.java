package ai.searchavo.search.config;

import ai.searchavo.platform.DailyJob;
import ai.searchavo.search.WebResultRetention;
import ai.searchavo.search.usecase.PurgeExpiredData;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SearchRetentionSchedule {

  @Bean("searchPurgeExpiredData")
  PurgeExpiredData searchPurgeExpiredData(
      WebResultRetention retention, Clock clock, SearchRetentionProperties periods) {
    return new PurgeExpiredData(retention, clock, periods.liveWebResults());
  }

  // UC-001 BR-009: a daily job deletes stored web results older than their retention.
  @Bean
  DailyJob searchRetention(PurgeExpiredData searchPurgeExpiredData) {
    return new DailyJob("search", searchPurgeExpiredData::purge);
  }
}
