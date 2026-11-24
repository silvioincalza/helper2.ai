package ai.searchavo.sources.huggingface;

import ai.searchavo.search.SourceQuery;
import org.springframework.web.util.UriBuilder;

final class HubListing {

  private HubListing() {}

  static UriBuilder of(UriBuilder u, SourceQuery query, String text) {
    if (!query.getBrowse() && !text.isBlank()) {
      u.queryParam("search", text);
    }
    if (query.getPublishedAfter() != null) {
      u.queryParam("sort", "trendingScore");
    }
    return u;
  }
}
