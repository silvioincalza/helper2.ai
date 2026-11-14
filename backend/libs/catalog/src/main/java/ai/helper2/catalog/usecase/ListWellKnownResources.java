package ai.helper2.catalog.usecase;

import ai.helper2.catalog.Resources;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ListWellKnownResources {
  public record Resource(
      UUID id, String title, String type, String audience, @Nullable String description) {}

  private final Resources store;

  public ListWellKnownResources(Resources store) {
    this.store = store;
  }

  public List<Resource> list(int limit, Collection<UUID> excluding) {
    return store.wellKnown(limit, excluding).stream()
        .map(r -> new Resource(r.id(), r.title(), r.type(), r.audience(), r.description()))
        .toList();
  }
}
