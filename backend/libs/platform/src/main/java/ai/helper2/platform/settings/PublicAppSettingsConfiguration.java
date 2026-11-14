package ai.helper2.platform.settings;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class PublicAppSettingsConfiguration {

  @Bean
  PublicAppSettings publicAppSettings(JdbcClient jdbc) {
    return new JdbcPublicAppSettings(jdbc);
  }
}
