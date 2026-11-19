package ai.helper2.site.pages.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.site.pages.config.IndexNowKeyProperties;
import ai.helper2.site.pages.config.PagesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IndexNowKeyFileTest {

  private static final String KEY = "0123456789abcdef0123456789abcdef";

  private static IndexNowKeyFile file(String key, boolean noindex) {
    return new IndexNowKeyFile(
        new IndexNowKeyProperties(key),
        new PagesProperties("https://helper2.ai", noindex, "file:/shell/index.html", 200_000));
  }

  @Test
  void servesTheKeyAtItsOwnAddress() {
    var res = file(KEY, false).keyFile(KEY);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).isEqualTo(KEY);
  }

  @Test
  void anyOtherNameNoKeyAndStagingAreNotFound() {
    assertThat(file(KEY, false).keyFile("abcdefgh12345678").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(file("", false).keyFile(KEY).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(file(KEY, true).keyFile(KEY).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
