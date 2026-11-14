package ai.helper2.site.pages.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.site.monetisation.Monetisation;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AdsTxtTest {

  private static CrawlerFiles files(Monetisation monetisation) {
    return new CrawlerFiles(null, null, monetisation);
  }

  @Test
  void namesGoogleAsTheSellerWhileAdSenseIsOn() {
    var adsense =
        new Monetisation(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("ca-pub-4688630163076563"),
            Optional.empty());

    var res = files(adsense).adsTxt();

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody())
        .isEqualTo("google.com, pub-4688630163076563, DIRECT, f08c47fec0942fa0\n");
  }

  @Test
  void notFoundWhileAdSenseIsOff() {
    assertThat(files(Monetisation.OFF).adsTxt().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void theBuiltShellAddressGoesHome() {
    var res = files(Monetisation.OFF).indexHtml();

    assertThat(res.getStatusCode().value()).isEqualTo(301);
    assertThat(res.getHeaders().getLocation()).hasToString("/");
  }

  @Test
  void pagesAreNeverStoredWhileAdSenseIsOn() {
    var adsense =
        new Monetisation(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("ca-pub-4688630163076563"),
            Optional.of("1234567890"));

    assertThat(PageRoutes.cacheControl(adsense).getHeaderValue()).isEqualTo("no-store");
    assertThat(PageRoutes.cacheControl(Monetisation.OFF).getHeaderValue()).isEqualTo("no-cache");
  }
}
