package ai.searchavo.site.pages.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.site.monetisation.CurrentMonetisation;
import ai.searchavo.site.monetisation.Monetisation;
import ai.searchavo.site.pages.config.PagesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LlmsTxtRouteTest {

  private static CrawlerFiles files(boolean noindex) {
    return new CrawlerFiles(
        null,
        new PagesProperties("https://searchavo.ai", noindex, "file:/shell/index.html", 200_000),
        new CurrentMonetisation(Monetisation.OFF, () -> true));
  }

  @Test
  void servesTheSummaryOnTheLiveSite() {
    var res = files(false).llmsTxt();
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getHeaders().getCacheControl()).isEqualTo("max-age=3600");
    assertThat(res.getBody())
        .startsWith("# searchavo.ai\n")
        .contains("https://searchavo.ai/sitemap.xml");
  }

  @Test
  void isNotFoundOnStaging() {
    assertThat(files(true).llmsTxt().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
