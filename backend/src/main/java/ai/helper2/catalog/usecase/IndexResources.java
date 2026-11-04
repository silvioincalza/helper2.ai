package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.search.MergedResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The Scout indexes what a collector found: the results are stored as INDEXED records of
 * [sourceId], and the resources whose records changed are returned (to enrich them).
 */
@Service
public class IndexResources {

  private final ResourceStore store;

  public IndexResources(ResourceStore store) {
    this.store = store;
  }

  public List<UUID> index(List<MergedResult> results, String sourceId) {
    List<UUID> changedRecords = store.upsert(results, DataOrigin.INDEXED, Set.of(sourceId));
    return store.resourcesOfRecords(changedRecords);
  }
}
