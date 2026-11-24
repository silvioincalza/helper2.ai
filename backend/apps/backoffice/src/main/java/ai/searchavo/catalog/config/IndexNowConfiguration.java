package ai.searchavo.catalog.config;

import ai.searchavo.catalog.usecase.ListIndexableResources;
import ai.searchavo.catalog.usecase.SendChangedPages;
import ai.searchavo.platform.config.HttpClientProperties;
import ai.searchavo.platform.http.ObservedHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IndexNowConfiguration {

  @Bean
  SendChangedPages sendChangedPages(
      IndexNowProperties p,
      ListIndexableResources resources,
      ObservationRegistry observations,
      HttpClientProperties http,
      Clock clock) {
    IndexNowNotice notice =
        new IndexNowNotice(
            ObservedHttp.restClientBuilder(p.timeout(), observations, http).build(),
            p.endpoint(),
            p.publicBaseUrl(),
            p.key());
    return new SendChangedPages(
        resources, notice, clock, p.publicBaseUrl(), p.window(), p.batch(), p.on());
  }
}
