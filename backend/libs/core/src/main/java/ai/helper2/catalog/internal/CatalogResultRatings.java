package ai.helper2.catalog.internal;

import ai.helper2.catalog.DirectoryRating;
import ai.helper2.catalog.ResourceRatings;
import ai.helper2.search.ResultRatings;
import ai.helper2.shared.ResourceId;
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
