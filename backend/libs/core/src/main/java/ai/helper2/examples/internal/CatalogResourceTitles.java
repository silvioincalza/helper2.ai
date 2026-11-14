package ai.helper2.examples.internal;

import ai.helper2.catalog.usecase.LookUpResourceTitles;
import ai.helper2.examples.ResourceTitles;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

class CatalogResourceTitles implements ResourceTitles {

  private final LookUpResourceTitles titles;

  CatalogResourceTitles(LookUpResourceTitles titles) {
    this.titles = titles;
  }

  @Override
  public Map<UUID, String> of(Collection<UUID> ids) {
    return titles.titles(ids);
  }
}
