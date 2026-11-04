package ai.helper2.catalog.internal;

import ai.helper2.search.DescribedSource;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Makes sure every configured source exists in the registry table before anything references it.
 */
@Component
class SourceRegistration implements SmartInitializingSingleton {

  private final ResourceStore store;
  private final List<DescribedSource> sources;

  SourceRegistration(ResourceStore store, List<DescribedSource> sources) {
    this.store = store;
    this.sources = sources;
  }

  @Override
  public void afterSingletonsInstantiated() {
    store.registerSources(sources.stream().map(DescribedSource::getDescriptor).toList());
  }
}
