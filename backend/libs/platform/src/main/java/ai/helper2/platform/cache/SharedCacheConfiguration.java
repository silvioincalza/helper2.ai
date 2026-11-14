package ai.helper2.platform.cache;

import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SharedCacheConfiguration {

  @Bean
  SharedCaches sharedCaches(DataSource dataSource, Resilience resilience, MeterRegistry meters) {
    return new SharedCaches(dataSource, resilience, meters);
  }
}
