package ai.helper2.sources.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.scout.PageScope;
import java.net.URI;
import org.junit.jupiter.api.Test;

class PublicSuffixDomainsTest {

  final PublicSuffixDomains domains = new PublicSuffixDomains();

  @Test
  void aSubdomainBelongsToItsRegistrableDomain() {
    assertThat(domains.registrable("docs.acme.com")).isEqualTo("acme.com");
    assertThat(domains.registrable("www.acme.co.uk")).isEqualTo("acme.co.uk");
  }

  @Test
  void aSharedHostIsItsOwnSite() {
    assertThat(domains.registrable("acme.github.io")).isEqualTo("acme.github.io");
    assertThat(domains.registrable("acme.vercel.app")).isEqualTo("acme.vercel.app");
  }

  @Test
  void aPublicSuffixAloneIsNoSite() {
    assertThat(domains.registrable("co.uk")).isNull();
  }

  @Test
  void theScopeOfAProductPageUsesIt() {
    PageScope scope = PageScope.of(URI.create("https://www.acme.co.uk/"), domains);

    assertThat(scope).isNotNull();
    assertThat(scope.allows(URI.create("https://docs.acme.co.uk/pricing"))).isTrue();
    assertThat(scope.allows(URI.create("https://evil.co.uk/pricing"))).isFalse();
  }
}
