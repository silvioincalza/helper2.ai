package ai.helper2.sources.config.scout;

import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.AwesomeListReader;
import ai.helper2.sources.config.SourcesProperties;
import ai.helper2.sources.github.scout.GitHubAwesomeListReader;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.SourceSettings;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AwesomeListReaderConfiguration {

  @Bean
  AwesomeListReader awesomeListReader(
      SourcesProperties p,
      Resilience resilience,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("github");
    return new GitHubAwesomeListReader(
        HttpClients.gitHubClient(CollectorsConfiguration.collectorClient(s, observations, http), s),
        resilience,
        clock);
  }
}
