package ai.searchavo.sources.config.scout;

import ai.searchavo.platform.config.HttpClientProperties;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.DiscoveredLists;
import ai.searchavo.scout.DiscoveryHostStore;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.sources.config.CollectorProperties;
import ai.searchavo.sources.config.DiscoveryCategories;
import ai.searchavo.sources.config.SourcesProperties;
import ai.searchavo.sources.liveweb.scout.DiscoveryCollector;
import ai.searchavo.sources.liveweb.scout.ScoutLiveWebCredits;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.sources.tavily.DiscoveryCategory;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

class DiscoveryCollectorRegistrar implements BeanRegistrar {
  static final String SETTINGS = "discovery";

  record Categories(List<DiscoveryCategory> all) {
    DiscoveryCategory get(String id) {
      return all.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
  }

  @Override
  public void register(BeanRegistry registry, Environment env) {
    DiscoveryCategories categories = DiscoveryCategories.bind(env);
    registry.registerBean(
        "discoveryCategories",
        Categories.class,
        spec ->
            spec.supplier(ctx ->
                new Categories(categories.resolve(ctx.bean(CollectorProperties.Discovery.class)))));
    for (String id : categories.ids()) {
      registry.registerBean(
          "discovery-" + id,
          DiscoveryCollector.class,
          spec -> spec.supplier(ctx -> collector(ctx, id)));
    }
  }

  private static DiscoveryCollector collector(BeanRegistry.SupplierContext ctx, String id) {
    SourceSettings s = ctx.bean(SourcesProperties.class).get(SETTINGS);
    CollectorProperties.Discovery d = ctx.bean(CollectorProperties.Discovery.class);
    Categories categories = ctx.bean(Categories.class);
    return new DiscoveryCollector(
        s,
        CollectorsConfiguration.collectorClient(
            s, ctx.bean(ObservationRegistry.class), ctx.bean(HttpClientProperties.class)),
        ctx.bean(Clock.class),
        ctx.bean(Resilience.class),
        new DiscoveryCollector.Search(d.searchDepth(), d.maxResults()),
        categories.get(id),
        categories.all(),
        ctx.bean(ScoutLiveWebCredits.class),
        ctx.bean(DiscoveryHostStore.class),
        ctx.bean(DiscoveredLists.class),
        ctx.bean(ShadowLibraries.class));
  }
}
