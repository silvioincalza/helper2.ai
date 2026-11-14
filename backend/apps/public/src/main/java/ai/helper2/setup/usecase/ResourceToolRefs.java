package ai.helper2.setup.usecase;

import ai.helper2.catalog.ResourceDetail;
import ai.helper2.search.SourceHit;
import ai.helper2.setup.ToolRef;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class ResourceToolRefs {

  private ResourceToolRefs() {}

  static ToolRef ref(ResourceDetail d) {
    return ToolRef.Companion.of(
        d.card().getTitle(), d.card().getType().name(), documentUrls(d), npmNames(d));
  }

  private static List<String> documentUrls(ResourceDetail d) {
    List<String> urls = new ArrayList<>();
    urls.add(d.card().getUrl().toString());
    for (SourceHit h : d.card().getHits()) {
      urls.add(h.getUrl().toString());
      if (h.getRepositoryUrl() != null) {
        urls.add(h.getRepositoryUrl().getValue());
      }
    }
    d.sourceRecords().forEach(r -> urls.add(r.url().toString()));
    return urls;
  }

  private static List<String> npmNames(ResourceDetail d) {
    return d.sourceRecords().stream()
        .filter(r -> "npm".equals(r.sourceId()))
        .map(ResourceDetail.SourceRecordInfo::externalId)
        .toList();
  }

  static @Nullable String registryName(ResourceDetail d) {
    return d.sourceRecords().stream()
        .filter(r -> "mcp-registry".equals(r.sourceId()))
        .map(ResourceDetail.SourceRecordInfo::externalId)
        .findFirst()
        .orElse(null);
  }
}
