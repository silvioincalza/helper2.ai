package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.ResourceStore;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The titles of catalog resources by id, for modules that name a resource without owning it. */
@Service
public class LookUpResourceTitles {

  private final ResourceStore store;

  public LookUpResourceTitles(ResourceStore store) {
    this.store = store;
  }

  /** The title of each id the catalog knows; an unknown id is absent. */
  public Map<UUID, String> titles(Collection<UUID> ids) {
    return store.titles(ids);
  }
}
