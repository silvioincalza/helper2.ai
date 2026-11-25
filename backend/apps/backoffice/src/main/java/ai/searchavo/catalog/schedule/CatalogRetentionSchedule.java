package ai.searchavo.catalog.schedule;

import ai.searchavo.catalog.usecase.PurgeExpiredData;
import ai.searchavo.platform.DailyJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CatalogRetentionSchedule {

  @Bean
  DailyJob catalogRetention(PurgeExpiredData catalogPurgeExpiredData) {
    return new DailyJob("catalog", catalogPurgeExpiredData::purge);
  }
}
