package ai.helper2.catalog;

import ai.helper2.shared.CanonicalUrl;
import java.util.List;

/**
 * The words of one list for the list index. {@code directory} says the list is a page of a
 * directory host: the public resource page counts such a list and never names it.
 */
public record AwesomeListText(
    CanonicalUrl url, String headings, List<Entry> entries, boolean directory) {

  public AwesomeListText(CanonicalUrl url, String headings, List<Entry> entries) {
    this(url, headings, entries, false);
  }

  public record Entry(CanonicalUrl url, String text, String headingPath) {}
}
