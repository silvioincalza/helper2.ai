package ai.helper2.catalog;

import ai.helper2.shared.CanonicalUrl;
import java.util.List;

/**
 * What one awesome list says around its entries, for search inside the list (ADR 0055): its own
 * page, its headings (one per line) and its entries. Plain words, already cleaned and capped by the
 * collector. The catalog turns them into tsvectors and keeps only those and the heading paths.
 */
public record AwesomeListText(CanonicalUrl url, String headings, List<Entry> entries) {

  /** One entry: the linked page, its line as plain words and its heading path. */
  public record Entry(CanonicalUrl url, String text, String headingPath) {}
}
