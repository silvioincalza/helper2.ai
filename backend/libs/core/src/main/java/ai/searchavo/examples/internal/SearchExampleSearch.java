package ai.searchavo.examples.internal;

import ai.searchavo.examples.ExampleCheck;
import ai.searchavo.examples.ExampleSearch;
import ai.searchavo.examples.SearchFindings;
import ai.searchavo.search.NoticeCode;
import ai.searchavo.search.SearchFilters;
import ai.searchavo.search.SearchOutcome;
import ai.searchavo.search.SearchRequest;
import ai.searchavo.search.SortOrder;
import ai.searchavo.search.usecase.SearchResources;
import ai.searchavo.shared.AudiencePath;

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
