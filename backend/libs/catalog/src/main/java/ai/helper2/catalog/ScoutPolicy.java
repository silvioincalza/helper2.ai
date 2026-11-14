package ai.helper2.catalog;

import java.time.Duration;

public record ScoutPolicy(
    Duration pageText,
    Duration pageRead,
    int productPages,
    int quoteChars,
    int articleExcerptChars,
    int productPageExcerptChars) {
  public static final int LINKED_PAGES = 3;

  public static final int QUOTE_CHARS = 300;

  public static final int MIN_TARGET_NAME_CHARS = 4;

  public static final String UNSTATED_VALUE = "the cited excerpt does not state the value";

  public static final int ARTICLE_EXCERPT_CHARS = 400;

  public static final int PRODUCT_PAGE_EXCERPT_CHARS = 2400;

  public static ScoutPolicy of(Duration pageText, Duration pageRead) {
    return new ScoutPolicy(
        pageText,
        pageRead,
        LINKED_PAGES + 1,
        QUOTE_CHARS,
        ARTICLE_EXCERPT_CHARS,
        PRODUCT_PAGE_EXCERPT_CHARS);
  }
}
