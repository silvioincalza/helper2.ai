package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.CatalogWriter;
import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.Resources;
import ai.searchavo.search.MergedResult;
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
