package ai.helper2.site.monetisation.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.api.generated.model.MonetisationDto;
import ai.helper2.site.monetisation.Monetisation;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SiteControllerTest {

  private static final Optional<String> ADSENSE_CLIENT = Optional.of("ca-pub-4688630163076563");

  private static MonetisationDto shown(Monetisation monetisation) {
    return new SiteController(monetisation).getSite().getBody().getMonetisation();
  }

  @Test
  void everyPartIsNullWhileOff() {
    MonetisationDto m = shown(Monetisation.OFF);

    assertThat(m.getGithubSponsorsUrl()).isNull();
    assertThat(m.getStripePaymentUrl()).isNull();
    assertThat(m.getGa4MeasurementId()).isNull();
    assertThat(m.getEthicalAdsPublisher()).isNull();
    assertThat(m.getAdsenseClient()).isNull();
    assertThat(m.getAdsenseSlot()).isNull();
  }

  @Test
  void thePartsThatAreOnAreShown() {
    MonetisationDto m =
        shown(
            new Monetisation(
                Optional.of(URI.create("https://github.com/sponsors/someone")),
                Optional.of(URI.create("https://buy.stripe.com/abc")),
                Optional.of("G-TEST1234"),
                Optional.of("helper2"),
                Optional.empty(),
                Optional.empty()));

    assertThat(m.getGithubSponsorsUrl()).hasToString("https://github.com/sponsors/someone");
    assertThat(m.getStripePaymentUrl()).hasToString("https://buy.stripe.com/abc");
    assertThat(m.getGa4MeasurementId()).isEqualTo("G-TEST1234");
    assertThat(m.getEthicalAdsPublisher()).isEqualTo("helper2");
  }

  @Test
  void theAdSensePublisherAloneStaysHiddenUntilTheAdUnitIsSetToo() {
    MonetisationDto publisherOnly = shown(adsense(Optional.empty()));
    MonetisationDto withAdUnit = shown(adsense(Optional.of("1234567890")));

    assertThat(publisherOnly.getAdsenseClient()).isNull();
    assertThat(withAdUnit.getAdsenseClient()).isEqualTo(ADSENSE_CLIENT.get());
    assertThat(withAdUnit.getAdsenseSlot()).isEqualTo("1234567890");
  }

  @Test
  void theAnswerIsTheSameForEveryoneAndCachedForFiveMinutes() {
    assertThat(new SiteController(Monetisation.OFF).getSite().getHeaders().getCacheControl())
        .isEqualTo("max-age=300, public");
  }

  private static Monetisation adsense(Optional<String> adUnit) {
    return new Monetisation(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        ADSENSE_CLIENT,
        adUnit);
  }
}
