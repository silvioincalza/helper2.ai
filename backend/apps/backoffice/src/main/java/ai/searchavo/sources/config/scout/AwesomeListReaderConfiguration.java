package ai.searchavo.sources.config.scout;

import ai.searchavo.platform.config.HttpClientProperties;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.AwesomeListReader;
import ai.searchavo.sources.config.SourcesProperties;
import ai.searchavo.sources.github.scout.GitHubAwesomeListReader;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.SourceSettings;
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
