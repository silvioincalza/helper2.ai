package ai.searchavo.examples.internal;

import ai.searchavo.catalog.usecase.ListWellKnownResources;
import ai.searchavo.examples.DraftSource;
import ai.searchavo.examples.DraftSources;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class CatalogDraftSources implements DraftSources {

  private final ListWellKnownResources resources;

  CatalogDraftSources(ListWellKnownResources resources) {
    this.resources = resources;
  }

  @Override
  public List<DraftSource> wellKnown(int limit, Set<UUID> excluding) {
    return resources.list(limit, excluding).stream()
        .map(r -> new DraftSource(r.id(), r.title(), r.type(), r.audience(), r.description()))
        .toList();
  }
}
