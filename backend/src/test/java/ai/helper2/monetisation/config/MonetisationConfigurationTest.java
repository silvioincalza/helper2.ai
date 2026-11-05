package ai.helper2.monetisation.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.monetisation.Monetisation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class MonetisationConfigurationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void emptyMeansOff() {
    assertThat(MonetisationConfiguration.from(new MonetisationProperties("", " ", null, "")))
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
                "helper2"));

    assertThat(m.githubSponsors()).hasValueSatisfying(u -> assertThat(u).hasHost("github.com"));
    assertThat(m.stripePayment()).isPresent();
    assertThat(m.analyticsOn()).isTrue();
    assertThat(m.adsOn()).isTrue();
  }

  @Test
  void validValuesPass() {
    assertThat(
            VALIDATOR.validate(
                new MonetisationProperties(
                    "https://github.com/sponsors/someone",
                    "https://donate.stripe.com/abc_123",
                    "G-ABC123XYZ9",
                    "helper2")))
        .isEmpty();
    assertThat(VALIDATOR.validate(new MonetisationProperties("", "", "", ""))).isEmpty();
  }

  @Test
  void aTypoFailsAtStartup() {
    assertThat(
            VALIDATOR.validate(
                new MonetisationProperties(
                    "http://github.com/sponsors/someone",
                    "https://evil.example/pay",
                    "UA-12345-1",
                    "Bad Publisher")))
        .hasSize(4);
  }
}
