package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.monetisation.Monetisation;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContentSecurityPolicyTest {

  private static Monetisation with(String ga4, String ads) {
    return new Monetisation(
        Optional.empty(), Optional.empty(), Optional.ofNullable(ga4), Optional.ofNullable(ads));
  }

  @Test
  void strictWhileNothingIsOn() {
    assertThat(ContentSecurityPolicy.forPublicSite(Monetisation.OFF))
        .isEqualTo(SecurityConfiguration.CSP);
  }

  @Test
  void analyticsAddsOnlyGoogleTagOriginsAndKeepsScriptsWithoutInlineOrEval() {
    String csp = ContentSecurityPolicy.forPublicSite(with("G-TEST1234", null));

    assertThat(csp)
        .contains("script-src 'self' https://www.googletagmanager.com;")
        .contains("connect-src 'self' https://*.google-analytics.com")
        .contains("style-src 'self';")
        .doesNotContain("unsafe-eval")
        .doesNotContain("ethicalads")
        .contains("frame-ancestors 'none'");
  }

  @Test
  void adsAddEthicalAdsAndInlineStylesOnly() {
    String csp = ContentSecurityPolicy.forPublicSite(with(null, "helper2"));

    assertThat(csp)
        .contains("script-src 'self' https://media.ethicalads.io https://server.ethicalads.io;")
        .contains("style-src 'self' 'unsafe-inline';")
        .doesNotContain("googletagmanager")
        .doesNotContain("script-src 'self' 'unsafe-inline'");
  }

  @Test
  void bothOnKeepEveryDirectiveOnce() {
    String csp = ContentSecurityPolicy.forPublicSite(with("G-TEST1234", "helper2"));

    assertThat(csp.split("script-src")).hasSize(2);
    assertThat(csp)
        .contains(
            "script-src 'self' https://www.googletagmanager.com https://media.ethicalads.io"
                + " https://server.ethicalads.io;");
  }
}
