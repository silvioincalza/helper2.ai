package ai.helper2.site.monetisation.config;

import ai.helper2.site.monetisation.Monetisation;
import java.net.URI;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MonetisationConfiguration {

  @Bean
  Monetisation monetisation(MonetisationProperties p) {
    return from(p);
  }

  static Monetisation from(MonetisationProperties p) {
    return new Monetisation(
        filled(p.githubSponsorsUrl()).map(URI::create),
        filled(p.stripePaymentUrl()).map(URI::create),
        filled(p.ga4MeasurementId()),
        filled(p.ethicalAdsPublisher()),
        filled(p.adsenseClient()),
        filled(p.adsenseSlot()));
  }

  private static Optional<String> filled(String value) {
    return Optional.ofNullable(value).map(String::strip).filter(v -> !v.isEmpty());
  }
}
