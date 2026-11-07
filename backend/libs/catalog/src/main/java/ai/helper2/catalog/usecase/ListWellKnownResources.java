package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.ResourceStore;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Resources a person would know, for modules that write about them (home example drafts). */
@Service
public class ListWellKnownResources {

  /** Public facts about one resource. */
  public record Resource(
      UUID id, String title, String type, String audience, @Nullable String description) {}

  private final ResourceStore store;

  public ListWellKnownResources(ResourceStore store) {
    this.store = store;
  }

  public List<Resource> list(int limit, Collection<UUID> excluding) {
    return store.wellKnown(limit, excluding).stream()
        .map(r -> new Resource(r.id(), r.title(), r.type(), r.audience(), r.description()))
        .toList();
  }
}
