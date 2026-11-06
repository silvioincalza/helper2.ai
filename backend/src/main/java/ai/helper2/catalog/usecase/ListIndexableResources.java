package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.ResourceStore;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The resource pages a sitemap lists (ADR 0050 §6); the rule itself belongs to the pages module.
 */
@Service
public class ListIndexableResources {

  /** One page: the resource and when it last changed. */
  public record Page(UUID id, String title, Instant lastModified) {}

  private final ResourceStore store;

  public ListIndexableResources(ResourceStore store) {
    this.store = store;
  }

  public List<Page> list(Collection<String> types, int promptMinLength, int limit) {
    return store.indexable(types, promptMinLength, limit).stream()
        .map(r -> new Page(r.id(), r.title(), r.lastModified()))
        .toList();
  }
}
