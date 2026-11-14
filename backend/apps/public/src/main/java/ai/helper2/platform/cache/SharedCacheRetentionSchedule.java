package ai.helper2.platform.cache;

import ai.helper2.platform.DailyJob;
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
