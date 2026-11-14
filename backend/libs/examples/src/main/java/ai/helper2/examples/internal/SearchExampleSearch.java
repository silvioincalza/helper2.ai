package ai.helper2.examples.internal;

import ai.helper2.examples.ExampleCheck;
import ai.helper2.examples.ExampleSearch;
import ai.helper2.examples.SearchFindings;
import ai.helper2.search.NoticeCode;
import ai.helper2.search.SearchFilters;
import ai.helper2.search.SearchOutcome;
import ai.helper2.search.SearchRequest;
import ai.helper2.search.SortOrder;
import ai.helper2.search.usecase.SearchResources;
import ai.helper2.shared.AudiencePath;

class SearchExampleSearch implements ExampleSearch {

  private final SearchResources search;

  SearchExampleSearch(SearchResources search) {
    this.search = search;
  }

  @Override
  public SearchFindings search(String query, AudiencePath path) {
    SearchOutcome outcome =
        search.search(
            new SearchRequest(
                query,
                path,
                new SearchFilters(),
                SortOrder.RELEVANCE,
                0,
                ExampleCheck.LOOKED_AT,
                null,
                false,
                null,
                null,
                false));
    boolean indexDown =
        outcome.getNotices().stream().anyMatch(n -> n.getCode() == NoticeCode.INDEX_UNAVAILABLE);
    return new SearchFindings(
        outcome.getResults().stream().map(r -> r.getId().getValue()).toList(),
        outcome.getTotal(),
        outcome.getCoverage().getPartial() || indexDown);
  }
}
