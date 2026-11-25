package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.CatalogWriter;
import ai.searchavo.search.DescribedSource;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component
class SourceRegistration implements SmartInitializingSingleton {

  private final CatalogWriter store;
  private final List<DescribedSource> sources;

  SourceRegistration(CatalogWriter store, List<DescribedSource> sources) {
    this.store = store;
    this.sources = sources;
  }

  @Override
  public void afterSingletonsInstantiated() {
    store.registerSources(sources.stream().map(DescribedSource::getDescriptor).toList());
  }
}
