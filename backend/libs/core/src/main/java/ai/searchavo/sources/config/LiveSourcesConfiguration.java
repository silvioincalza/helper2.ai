package ai.searchavo.sources.config;

import ai.searchavo.platform.config.HttpClientProperties;
import ai.searchavo.platform.http.ObservedHttp;
import ai.searchavo.platform.resilience.OutboundThrottle;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.search.VitalityPolicy;
import ai.searchavo.sources.arxiv.ArxivSource;
import ai.searchavo.sources.github.GitHubSource;
import ai.searchavo.sources.hackernews.HackerNewsSource;
import ai.searchavo.sources.huggingface.HuggingFaceModelsSource;
import ai.searchavo.sources.huggingface.HuggingFaceSpacesSource;
import ai.searchavo.sources.npm.NpmSource;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.sources.tavily.TavilySource;
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
  HackerNewsSource hackerNewsSource(
      SourcesProperties p,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http,
      ShadowLibraries shadowLibraries) {
    SourceSettings s = p.get("hacker-news");
    return new HackerNewsSource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations, http), clock, shadowLibraries);
  }

  @Bean
  TavilySource tavilySource(
      SourcesProperties p,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http,
      ShadowLibraries shadowLibraries) {
    SourceSettings s = p.get("live-web");
    return new TavilySource(
        s, ObservedHttp.restClientBuilder(s.timeout(), observations, http), clock, shadowLibraries);
  }
}
