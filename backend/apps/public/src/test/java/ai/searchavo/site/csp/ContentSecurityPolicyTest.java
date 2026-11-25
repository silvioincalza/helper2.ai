package ai.searchavo.site.csp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.site.monetisation.Monetisation;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContentSecurityPolicyTest {

  private static Monetisation with(String ga4) {
    return new Monetisation(
        Optional.empty(),
        Optional.empty(),
        Optional.ofNullable(ga4),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void strictWhileNothingIsOn() {
    assertThat(ContentSecurityPolicy.forPublicSite(Monetisation.OFF))
        .isEqualTo(ContentSecurityPolicy.STRICT);
  }

  @Test
  void analyticsAddsOnlyGoogleTagOriginsAndKeepsScriptsWithoutInlineOrEval() {
    String csp = ContentSecurityPolicy.forPublicSite(with("G-TEST1234"));

    assertThat(csp)
        .contains("script-src 'self' https://www.googletagmanager.com;")
        .contains("connect-src 'self' https://*.google-analytics.com")
        .contains("style-src 'self';")
        .doesNotContain("unsafe-eval")
        .contains("frame-ancestors 'none'");
  }

  @Test
  void adSenseNeedsThePublisherAndTheAdUnit() {
    Monetisation publisherOnly =
        new Monetisation(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("ca-pub-4688630163076563"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    assertThat(ContentSecurityPolicy.forPublicSite(publisherOnly))
        .isEqualTo(ContentSecurityPolicy.STRICT);
  }

  @Test
  void adSenseUsesTheNoncePolicy() {
    Monetisation adsense =
        new Monetisation(
            Optional.empty(),
            Optional.empty(),
            Optional.of("G-TEST1234"),
            Optional.of("ca-pub-4688630163076563"),
            Optional.of("1234567890"),
            Optional.empty(),
            Optional.empty());

    assertThat(ContentSecurityPolicy.forPublicSite(adsense))
        .contains("script-src 'nonce-{nonce}' 'unsafe-inline' 'unsafe-eval' 'strict-dynamic'")
        .contains("object-src 'none'")
        .contains("base-uri 'none'")
        .contains("frame-ancestors 'none'")
        .doesNotContain("script-src 'self'");
  }
}
