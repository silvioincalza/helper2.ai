package ai.searchavo.site.monetisation.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.site.monetisation.Monetisation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class MonetisationConfigurationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void thePublisherAloneOnlyAnswersTheSiteCheck() {
    Monetisation m =
        MonetisationConfiguration.from(
            new MonetisationProperties("", "", "", "ca-pub-4688630163076563", "", "", "", true));

    assertThat(m.adsTxt()).isPresent();
    assertThat(m.adsenseOn()).isFalse();
  }

  @Test
  void oneAdUnitIsEnoughToTurnAdSenseOn() {
    Monetisation searchOnly =
        MonetisationConfiguration.from(
            new MonetisationProperties(
                "", "", "", "ca-pub-4688630163076563", "", "2345678901", "", true));

    assertThat(searchOnly.adsenseOn()).isTrue();
    assertThat(searchOnly.adsenseSlot()).isEmpty();
    assertThat(searchOnly.adsenseSlotSearch()).contains("2345678901");

    Monetisation guideOnly =
        MonetisationConfiguration.from(
            new MonetisationProperties(
                "", "", "", "ca-pub-4688630163076563", "", "", "3456789012", true));

    assertThat(guideOnly.adsenseOn()).isTrue();
    assertThat(guideOnly.adsenseSlotGuide()).contains("3456789012");
  }

  @Test
  void aSearchAdUnitWithTheWrongShapeFailsAtStartup() {
    assertThat(
            VALIDATOR.validate(
                new MonetisationProperties(
                    "", "", "", "ca-pub-4688630163076563", "", "slot-2", "", true)))
        .hasSize(1);
  }

  @Test
  void noAdsTxtWhileAdSenseIsOff() {
    assertThat(Monetisation.OFF.adsTxt()).isEmpty();
  }

  @Test
  void emptyMeansOff() {
    assertThat(
            MonetisationConfiguration.from(
                new MonetisationProperties("", " ", null, "", "", "", "", true)))
        .isEqualTo(Monetisation.OFF);
  }

  @Test
  void filledPartsAreOn() {
    Monetisation m =
        MonetisationConfiguration.from(
            new MonetisationProperties(
                "https://github.com/sponsors/someone",
                "https://buy.stripe.com/abc123",
                "G-ABC123XYZ9",
                "ca-pub-4688630163076563",
                "1234567890",
                "",
                "",
                true));

    assertThat(m.adsenseOn()).isTrue();
    assertThat(m.adsTxt()).contains("google.com, pub-4688630163076563, DIRECT, f08c47fec0942fa0");
    assertThat(m.githubSponsors()).hasValueSatisfying(u -> assertThat(u).hasHost("github.com"));
    assertThat(m.stripePayment()).isPresent();
    assertThat(m.analyticsOn()).isTrue();
  }

  @Test
  void validValuesPass() {
    assertThat(
            VALIDATOR.validate(
                new MonetisationProperties(
                    "https://github.com/sponsors/someone",
                    "https://donate.stripe.com/abc_123",
                    "G-ABC123XYZ9",
                    "ca-pub-4688630163076563",
                    "1234567890",
                    "",
                    "",
                    true)))
        .isEmpty();
    assertThat(VALIDATOR.validate(new MonetisationProperties("", "", "", "", "", "", "", true)))
        .isEmpty();
  }

  @Test
  void aTypoFailsAtStartup() {
    assertThat(
            VALIDATOR.validate(
                new MonetisationProperties(
                    "http://github.com/sponsors/someone",
                    "https://evil.example/pay",
                    "UA-12345-1",
                    "pub-4688630163076563",
                    "slot-1",
                    "",
                    "",
                    true)))
        .hasSize(5);
  }
}
