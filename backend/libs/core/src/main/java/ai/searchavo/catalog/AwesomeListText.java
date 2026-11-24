package ai.searchavo.catalog;

import ai.searchavo.shared.CanonicalUrl;
import java.util.List;

public record AwesomeListText(CanonicalUrl url, String headings, List<Entry> entries) {
  public record Entry(CanonicalUrl url, String text, String headingPath) {}
}
