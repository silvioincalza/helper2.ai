package ai.helper2.catalog.config;

import ai.helper2.catalog.usecase.ListIndexableResources;
import ai.helper2.catalog.usecase.SendChangedPages;
import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.http.ObservedHttp;
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
