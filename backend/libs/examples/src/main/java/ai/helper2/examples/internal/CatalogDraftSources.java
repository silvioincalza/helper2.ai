package ai.helper2.examples.internal;

import ai.helper2.catalog.usecase.ListWellKnownResources;
import ai.helper2.examples.DraftSource;
import ai.helper2.examples.DraftSources;
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
