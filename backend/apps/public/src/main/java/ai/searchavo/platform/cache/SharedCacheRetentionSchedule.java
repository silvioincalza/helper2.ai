package ai.searchavo.platform.cache;

import ai.searchavo.platform.DailyJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class SharedCacheRetentionSchedule {

  @Bean
  DailyJob sharedCacheRetention(JdbcClient jdbc) {
    return new DailyJob("shared-cache", new SharedCacheRetention(jdbc)::purge);
  }
}
