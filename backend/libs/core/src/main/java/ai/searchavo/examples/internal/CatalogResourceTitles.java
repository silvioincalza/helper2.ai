package ai.searchavo.examples.internal;

import ai.searchavo.catalog.usecase.LookUpResourceTitles;
import ai.searchavo.examples.ResourceTitles;
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
