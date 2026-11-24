package ai.searchavo.site.monetisation.config;

import ai.searchavo.platform.settings.RuntimeSetting;
import ai.searchavo.platform.settings.RuntimeSettings;
import ai.searchavo.site.monetisation.CurrentMonetisation;
import ai.searchavo.site.monetisation.Monetisation;
import java.net.URI;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MonetisationConfiguration {

  @Bean
  CurrentMonetisation currentMonetisation(MonetisationProperties p, RuntimeSettings runtime) {
    return new CurrentMonetisation(
        from(p), runtime.declareSwitch(RuntimeSetting.ADS_ENABLED, p.adsEnabled()));
  }

  static Monetisation from(MonetisationProperties p) {
    return new Monetisation(
        filled(p.githubSponsorsUrl()).map(URI::create),
        filled(p.stripePaymentUrl()).map(URI::create),
        filled(p.ga4MeasurementId()),
        filled(p.adsenseClient()),
        filled(p.adsenseSlot()),
        filled(p.adsenseSlotSearch()),
        filled(p.adsenseSlotGuide()));
  }

  private static Optional<String> filled(String value) {
    return Optional.ofNullable(value).map(String::strip).filter(v -> !v.isEmpty());
  }
}
