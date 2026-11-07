package ai.helper2.sources.github;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.scout.SeedDiscoveryHosts;
import ai.helper2.sources.github.ProductLinks.Skip;
import ai.helper2.sources.tavily.DiscoveryPages;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductLinksTest {

  private static final DiscoveryPages PAGES = new DiscoveryPages(SeedDiscoveryHosts.LISTS);

  final ProductLinks links = new ProductLinks(List.of("youtube.com", "producthunt.com"));

  @ParameterizedTest
  @CsvSource({
    "https://www.Acme.ai/?utm_source=list#top, https://acme.ai",
    "https://acme.ai/pricing, https://acme.ai",
    "https://aws.amazon.com/bedrock/, https://aws.amazon.com/bedrock",
    "https://acme.ai/studio?plan=pro, https://acme.ai/studio",
    "https://acme.ai., https://acme.ai",
  })
  void aProductLinkBecomesItsHomePageOrItsOneLevelPage(String raw, String site) {
    ProductLinks.Checked checked = links.check(raw, PAGES);

    assertThat(checked.skip()).isNull();
    assertThat(checked.site().getValue()).isEqualTo(site);
  }

  @ParameterizedTest
  @CsvSource({
    // Only https.
    "http://acme.ai, NOT_HTTPS",
    "'data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==', NOT_HTTPS",
    "javascript:alert(1), NOT_HTTPS",
    "mailto:hi@acme.ai, NOT_HTTPS",
    "//acme.ai/, NOT_HTTPS",
    // A name, never an address.
    "https://93.184.216.34/, IP_ADDRESS",
    "'https://[::1]/', IP_ADDRESS",
    "https://127.1/, IP_ADDRESS",
    "https://0x7f.0x0.0x0.0x1/, IP_ADDRESS",
    "https://2130706433.0/, IP_ADDRESS",
    // No user info, no port.
    "https://user:secret@acme.ai/, USER_INFO",
    "https://acme.ai@evil.ai/, USER_INFO",
    "https://acme.ai:8443/, PORT",
    // No private or reserved names.
    "https://localhost/, PRIVATE_HOST",
    "https://printer.local/, PRIVATE_HOST",
    "https://metadata.google.internal/, PRIVATE_HOST",
    "https://wiki.corp/, PRIVATE_HOST",
    "https://router.home.arpa/, PRIVATE_HOST",
    // Look-alike names.
    "https://xn--pple-43d.com/, PUNYCODE",
    "https://www.xn--80ak6aa92e.com/, PUNYCODE",
    "https://acme.xn--p1ai/, PUNYCODE",
    // A link that sends you elsewhere.
    "https://acme.ai/redirect?to=https://evil.ai, REDIRECT",
    "https://acme.ai/out/https%3A%2F%2Fevil.ai, REDIRECT",
    "https://acme.ai/?url=https%3a%2f%2fevil.ai, REDIRECT",
    "https://acme.ai//evil.ai, REDIRECT",
    "https://acme.ai/go?id=7, REDIRECT",
    "https://bit.ly/3abc, REDIRECT",
    "https://t.co/xyz, REDIRECT",
    // GitHub's own pages are entries or nothing.
    "https://gist.github.com/acme/1, GITHUB",
    "https://docs.github.com/en, GITHUB",
    "https://raw.githubusercontent.com/acme/x/main/a.md, GITHUB",
    // Excluded domains, subdomains included.
    "https://www.youtube.com/watch?v=1, EXCLUDED_DOMAIN",
    "https://producthunt.com/posts/acme, EXCLUDED_DOMAIN",
    // Writing about a product, not the product.
    "https://acme.ai/blog/launch, NOT_A_PRODUCT_PAGE",
    "https://docs.acme.ai/start, NOT_A_PRODUCT_PAGE",
    "https://acme.ai/docs, NOT_A_PRODUCT_PAGE",
    "https://acme.ai/a/b, NOT_A_PRODUCT_PAGE",
    "https://medium.com/@acme, NOT_A_PRODUCT_PAGE",
    "https://acme.ai/how-we-built-our-ai-stack, NOT_A_PRODUCT_PAGE",
    // Not a URL at all.
    "'https://acme .ai/', MALFORMED",
    "https://acme_ai.com/, MALFORMED",
    "'', MALFORMED",
  })
  void aHostileOrNonProductLinkIsSkippedWithItsReason(String raw, Skip reason) {
    ProductLinks.Checked checked = links.check(raw, PAGES);

    assertThat(checked.site()).isNull();
    assertThat(checked.skip()).isEqualTo(reason);
  }

  @Test
  void aVeryLongLinkIsSkipped() {
    assertThat(links.check("https://acme.ai/" + "a".repeat(3000), PAGES).skip())
        .isEqualTo(Skip.MALFORMED);
  }

  @Test
  void aControlOrInvisibleCharacterIsSkipped() {
    assertThat(links.check("https://acme.ai/\u0000", PAGES).skip()).isEqualTo(Skip.MALFORMED);
    assertThat(links.check("https://acme‮.ai/", PAGES).skip()).isEqualTo(Skip.MALFORMED);
  }

  @Test
  void theReasonIsAMetricTag() {
    assertThat(Skip.NOT_A_PRODUCT_PAGE.tag()).isEqualTo("not-a-product-page");
  }
}
