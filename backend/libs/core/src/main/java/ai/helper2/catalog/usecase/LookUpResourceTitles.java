package ai.helper2.catalog.usecase;

import ai.helper2.catalog.Resources;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public class LookUpResourceTitles {

  private final Resources store;

  public LookUpResourceTitles(Resources store) {
    this.store = store;
  }

  public Map<UUID, String> titles(Collection<UUID> ids) {
    return store.titles(ids);
  }
}
