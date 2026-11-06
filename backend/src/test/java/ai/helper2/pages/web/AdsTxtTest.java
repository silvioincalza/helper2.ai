package ai.helper2.pages.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.monetisation.Monetisation;
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
}
