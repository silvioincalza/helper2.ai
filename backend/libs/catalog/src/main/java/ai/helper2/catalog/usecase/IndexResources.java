package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.Resources;
import ai.helper2.search.MergedResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class IndexResources {

  private final CatalogWriter writer;
  private final Resources resources;

  public IndexResources(CatalogWriter writer, Resources resources) {
    this.writer = writer;
    this.resources = resources;
  }

  public List<UUID> index(List<MergedResult> results, String sourceId) {
    List<UUID> changedRecords = writer.upsert(results, DataOrigin.INDEXED, Set.of(sourceId));
    return resources.resourcesOfRecords(changedRecords);
  }
}
