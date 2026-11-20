package ai.helper2.site.monetisation.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.site.monetisation.Monetisation;
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
            new MonetisationProperties("", "", "", "ca-pub-4688630163076563", "", "", ""));

    assertThat(m.adsTxt()).isPresent();
    assertThat(m.adsenseOn()).isFalse();
  }

  @Test
  void oneAdUnitIsEnoughToTurnAdSenseOn() {
    Monetisation searchOnly =
        MonetisationConfiguration.from(
            new MonetisationProperties(
                "", "", "", "ca-pub-4688630163076563", "", "2345678901", ""));

    assertThat(searchOnly.adsenseOn()).isTrue();
    assertThat(searchOnly.adsenseSlot()).isEmpty();
    assertThat(searchOnly.adsenseSlotSearch()).contains("2345678901");

    Monetisation guideOnly =
        MonetisationConfiguration.from(
            new MonetisationProperties(
                "", "", "", "ca-pub-4688630163076563", "", "", "3456789012"));

    assertThat(guideOnly.adsenseOn()).isTrue();
    assertThat(guideOnly.adsenseSlotGuide()).contains("3456789012");
  }

  @Test
  void aSearchAdUnitWithTheWrongShapeFailsAtStartup() {
    assertThat(
            VALIDATOR.validate(
                new MonetisationProperties(
                    "", "", "", "ca-pub-4688630163076563", "", "slot-2", "")))
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
                new MonetisationProperties("", " ", null, "", "", "", "")))
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
                ""));

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
                    "")))
        .isEmpty();
    assertThat(VALIDATOR.validate(new MonetisationProperties("", "", "", "", "", "", "")))
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
                    "")))
        .hasSize(5);
  }
}
