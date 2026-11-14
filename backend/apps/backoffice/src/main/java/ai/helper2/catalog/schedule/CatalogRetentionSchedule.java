package ai.helper2.catalog.schedule;

import ai.helper2.catalog.usecase.PurgeExpiredData;
import ai.helper2.platform.DailyJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CatalogRetentionSchedule {

  @Bean
  DailyJob catalogRetention(PurgeExpiredData catalogPurgeExpiredData) {
    return new DailyJob("catalog", catalogPurgeExpiredData::purge);
  }
}
