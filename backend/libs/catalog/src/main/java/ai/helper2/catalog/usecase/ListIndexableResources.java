package ai.helper2.catalog.usecase;

import ai.helper2.catalog.Resources;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class ListIndexableResources {
  public record Page(UUID id, String title, Instant lastModified) {}

  private final Resources store;

  public ListIndexableResources(Resources store) {
    this.store = store;
  }

  public List<Page> list(Collection<String> types, int promptMinLength, int limit) {
    return store.indexable(types, promptMinLength, limit).stream()
        .map(r -> new Page(r.id(), r.title(), r.lastModified()))
        .toList();
  }
}
