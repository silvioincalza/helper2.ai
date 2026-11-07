package ai.helper2.catalog;

import java.time.Duration;

/**
 * What the Scout reads from the web and how long it keeps it, as the Privacy page states it (GET
 * /api/v1/policy, ADR 0029). The Scout runs in the back office and the policy endpoint in the
 * public app, so its numbers live here, next to the catalog the Scout fills (ADR 0032): the Scout's
 * code reads its limits from these constants, and the two apps cannot state different numbers.
 *
 * @param pageText the cleaned text of a product's own page, kept to try again
 * @param pageRead a page's address and the fingerprint of its text (ADR 0023)
 * @param productPages how many pages of a product's own website one enrichment reads
 * @param quoteChars the longest quote the evidence gate lets through
 * @param articleExcerptChars the longest excerpt kept of an article found by web search
 * @param productPageExcerptChars the longest excerpt kept of a product page found by web search
 */
public record ScoutPolicy(
    Duration pageText,
    Duration pageRead,
    int productPages,
    int quoteChars,
    int articleExcerptChars,
    int productPageExcerptChars) {

  /** The links of a product's home page one enrichment follows (the home page is one more). */
  public static final int LINKED_PAGES = 3;

  /** The longest quote the evidence gate lets through. */
  public static final int QUOTE_CHARS = 300;

  /** The Tavily search source keeps this much of an article (ADR 0014). */
  public static final int ARTICLE_EXCERPT_CHARS = 400;

  /** The longest excerpt kept of a product page found by web search. */
  public static final int PRODUCT_PAGE_EXCERPT_CHARS = 2400;

  /** The policy with the Scout's retention periods (helper2.scout.evidence). */
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
