package ai.helper2.search.config;

import ai.helper2.platform.DailyJob;
import ai.helper2.search.WebResultRetention;
import ai.helper2.search.usecase.PurgeExpiredData;
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

  @Bean
  DailyJob searchRetention(PurgeExpiredData searchPurgeExpiredData) {
    return new DailyJob("search", searchPurgeExpiredData::purge);
  }
}
