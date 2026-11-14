package ai.helper2.sources.config;

import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.OutboundThrottle;
import ai.helper2.search.VitalityPolicy;
import ai.helper2.sources.arxiv.ArxivSource;
import ai.helper2.sources.github.GitHubSource;
import ai.helper2.sources.huggingface.HuggingFaceModelsSource;
import ai.helper2.sources.huggingface.HuggingFaceSpacesSource;
import ai.helper2.sources.npm.NpmSource;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.TavilySource;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SourcesProperties.class)
@Profile("!demo")
class LiveSourcesConfiguration {

  @Bean
  GitHubSource gitHubSource(
      SourcesProperties p,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("github");
    return new GitHubSource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations, http), clock);
  }

  @Bean
  HuggingFaceModelsSource huggingFaceModelsSource(
      SourcesProperties p,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("huggingface");
    return new HuggingFaceModelsSource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations, http), clock);
  }

  @Bean
  HuggingFaceSpacesSource huggingFaceSpacesSource(
      SourcesProperties p,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http,
      VitalityPolicy vitality) {
    SourceSettings s = p.get("huggingface");
    return new HuggingFaceSpacesSource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations, http), clock, vitality);
  }

  @Bean
  NpmSource npmSource(
      SourcesProperties p,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("npm");
    return new NpmSource(s, ObservedHttp.restClientBuilder(s.timeout(), observations, http), clock);
  }

  @Bean
  ArxivSource arxivSource(
      SourcesProperties p,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http,
      OutboundThrottle throttle) {
    SourceSettings s = p.get("arxiv");
    return new ArxivSource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations, http), clock, throttle);
  }

  @Bean
  TavilySource tavilySource(
      SourcesProperties p,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("live-web");
    return new TavilySource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations, http), clock);
  }
}
