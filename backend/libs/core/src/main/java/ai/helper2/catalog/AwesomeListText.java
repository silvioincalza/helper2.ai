package ai.helper2.catalog;

import ai.helper2.shared.CanonicalUrl;
import java.util.List;

public record AwesomeListText(CanonicalUrl url, String headings, List<Entry> entries) {
  public record Entry(CanonicalUrl url, String text, String headingPath) {}
}
