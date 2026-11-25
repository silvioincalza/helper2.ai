package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.DirectoryRating;
import ai.searchavo.catalog.ResourceRatings;
import ai.searchavo.search.ResultRatings;
import ai.searchavo.shared.ResourceId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** UC-001 BR-023: the ranking reads the rating the resource page shows. */
@Component
class CatalogResultRatings implements ResultRatings {

  private final ResourceRatings ratings;

  CatalogResultRatings(ResourceRatings ratings) {
    this.ratings = ratings;
  }

  @Override
  public Map<ResourceId, Double> of(Collection<ResourceId> ids) {
    Map<ResourceId, Double> scores = new HashMap<>();
    ratings
        .stated(ids.stream().map(ResourceId::getValue).toList())
        .forEach((id, stated) ->
            DirectoryRating.of(stated)
                .ifPresent(rating -> scores.put(new ResourceId(id), rating.score())));
    return scores;
  }
}
