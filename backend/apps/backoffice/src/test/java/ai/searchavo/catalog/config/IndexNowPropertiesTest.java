package ai.searchavo.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IndexNowPropertiesTest {

  private static IndexNowProperties of(String key, boolean noindex) {
    return new IndexNowProperties(
        key,
        noindex,
        "https://searchavo.ai",
        "0 47 5 * * *",
        URI.create("https://api.indexnow.org/indexnow"),
        Duration.ofHours(25),
        Duration.ofSeconds(20),
        10_000);
  }

  @Test
  void onOnlyWithAKeyOnAnIndexedSite() {
    assertThat(of("0123456789abcdef", false).on()).isTrue();
    assertThat(of("", false).on()).isFalse();
    assertThat(of("0123456789abcdef", true).on()).isFalse();
  }
}
