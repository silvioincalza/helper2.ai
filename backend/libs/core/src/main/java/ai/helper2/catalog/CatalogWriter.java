package ai.helper2.catalog;

import ai.helper2.search.MergedResult;
import ai.helper2.search.SourceDescriptor;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface CatalogWriter {
  void registerSources(Collection<SourceDescriptor> descriptors);

  List<UUID> upsert(List<MergedResult> results, DataOrigin origin, Set<String> persistableSources);

  Forgotten forgetRecords(String sourceId, Collection<String> externalIds);

  record Forgotten(int records, int resources, List<UUID> keptResources) {
    public static final Forgotten NONE = new Forgotten(0, 0, List.of());
  }
}
