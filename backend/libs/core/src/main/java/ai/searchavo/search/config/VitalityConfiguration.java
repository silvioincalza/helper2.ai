package ai.searchavo.search.config;

import ai.searchavo.search.VitalityPolicy;
import ai.searchavo.search.VitalityRules;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VitalityProperties.class)
class VitalityConfiguration {

  @Bean
  VitalityPolicy vitalityPolicy(VitalityProperties p) {
    return new VitalityPolicy(
        new VitalityRules(
            p.hostedAppMinLikes(), p.forkMinStars(), p.maintainedWithin(), p.establishedStars()));
  }
}
