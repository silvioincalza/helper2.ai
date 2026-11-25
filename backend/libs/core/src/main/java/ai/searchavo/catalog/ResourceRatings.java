package ai.searchavo.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The figures directories state for resources of the catalog. */
public interface ResourceRatings {

  /** The stated figures of each resource that has some; a resource with none is not a key. */
  Map<UUID, List<DirectoryRating.Stated>> stated(Collection<UUID> resourceIds);

  default List<DirectoryRating.Stated> stated(UUID resourceId) {
    return stated(List.of(resourceId)).getOrDefault(resourceId, List.of());
  }
}
