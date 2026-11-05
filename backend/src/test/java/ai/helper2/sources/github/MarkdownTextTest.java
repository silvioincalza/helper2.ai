package ai.helper2.sources.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownTextTest {

  @Test
  void keepsLinkTextAndDropsTheUrl() {
    assertThat(MarkdownText.plain("- [Qdrant](https://github.com/qdrant/qdrant) - Vector search."))
        .isEqualTo("Qdrant - Vector search.");
  }

  @Test
  void dropsBadgesAndImagesEvenInsideLinks() {
    assertThat(
            MarkdownText.plain(
                "[![Stars](https://img.shields.io/github/stars/a/b.svg)](https://github.com/a/b)"
                    + " Fast ![logo](logo.png) tool"))
        .isEqualTo("Fast tool");
  }

  @Test
  void dropsHtmlTagsEntitiesAndBareUrls() {
    assertThat(
            MarkdownText.plain(
                "<img src=\"x.png\"/> <b>Bold</b> &amp; see https://example.com/x or www.example.org"))
        .isEqualTo("Bold see or");
  }

  @Test
  void dropsControlBidiAndZeroWidthCharacters() {
    String hidden =
        "evil" + c(0x202E) + "txt.exe" + c(0x0) + " zero" + c(0x200B) + "width" + c(0x2066) + "x"
            + c(0x2069);
    assertThat(MarkdownText.plain(hidden)).isEqualTo("eviltxt.exe zerowidthx");
  }

  @Test
  void dropsMarkdownMarkersAndCollapsesSpaces() {
    assertThat(MarkdownText.plain("| **Storm** | `cli`  ~~old~~ |")).isEqualTo("Storm cli old");
  }

  @Test
  void cutsLongTextOnACodePointBoundary() {
    String text = "a".repeat(9) + c(0x1F600) + "b".repeat(10);

    assertThat(MarkdownText.cut(text, 10)).isEqualTo("a".repeat(9) + c(0x1F600));
    assertThat(MarkdownText.cut("short", 10)).isEqualTo("short");
  }

  /** One code point as a string: invisible characters stay visible in the source. */
  private static String c(int codePoint) {
    return Character.toString(codePoint);
  }
}
