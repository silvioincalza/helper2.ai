package ai.helper2.sources.huggingface;

import ai.helper2.search.SourceQuery;
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
