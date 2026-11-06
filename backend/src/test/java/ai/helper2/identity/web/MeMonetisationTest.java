package ai.helper2.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.monetisation.Monetisation;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MeMonetisationTest {

  private static final Monetisation ALL_ON =
      new Monetisation(
          Optional.of(URI.create("https://github.com/sponsors/someone")),
          Optional.of(URI.create("https://buy.stripe.com/abc")),
          Optional.of("G-TEST1234"),
          Optional.of("helper2"),
          Optional.empty());

  @Test
  void nullWhileOff() {
    var m = MeController.monetisation(Monetisation.OFF, false);

    assertThat(m.getGithubSponsorsUrl()).isNull();
    assertThat(m.getStripePaymentUrl()).isNull();
    assertThat(m.getGa4MeasurementId()).isNull();
    assertThat(m.getEthicalAdsPublisher()).isNull();
  }

  @Test
  void thePartsThatAreOn() {
    var m = MeController.monetisation(ALL_ON, false);

    assertThat(m.getGithubSponsorsUrl()).hasToString("https://github.com/sponsors/someone");
    assertThat(m.getStripePaymentUrl()).hasToString("https://buy.stripe.com/abc");
    assertThat(m.getGa4MeasurementId()).isEqualTo("G-TEST1234");
    assertThat(m.getEthicalAdsPublisher()).isEqualTo("helper2");
  }

  @Test
  void theAdminHostNeverGetsThem() {
    var m = MeController.monetisation(ALL_ON, true);

    assertThat(m.getGithubSponsorsUrl()).isNull();
    assertThat(m.getGa4MeasurementId()).isNull();
    assertThat(m.getEthicalAdsPublisher()).isNull();
  }
}
