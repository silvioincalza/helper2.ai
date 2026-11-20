package ai.helper2.site.monetisation.config;

import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettings;
import ai.helper2.site.monetisation.CurrentMonetisation;
import ai.helper2.site.monetisation.Monetisation;
import java.net.URI;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MonetisationConfiguration {

  @Bean
  CurrentMonetisation currentMonetisation(MonetisationProperties p, RuntimeSettings runtime) {
    IntSupplier adsEnabled = runtime.declare(RuntimeSetting.ADS_ENABLED, p.adsEnabled() ? 1 : 0);
    return new CurrentMonetisation(from(p), () -> adsEnabled.getAsInt() > 0);
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
