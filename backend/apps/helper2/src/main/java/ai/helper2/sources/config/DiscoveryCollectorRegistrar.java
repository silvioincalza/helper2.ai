package ai.helper2.sources.config;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.DiscoveryHostStore;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.DiscoveryCategory;
import ai.helper2.sources.tavily.ScoutTavilyCredits;
import ai.helper2.sources.tavily.TavilyDiscoveryCollector;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * One {@link TavilyDiscoveryCollector} bean per entry of {@code helper2.categories} (ADR 0051),
 * named {@code discovery-<id>}: each category is its own collector, with its own cursor, run record
 * and dot on the Home radar.
 *
 * <p>How many beans there are depends on the configuration, so the list is read here, before any
 * bean exists, through Spring Boot's {@link Binder} on the {@link Environment}: the one place that
 * does not take its settings as a {@code @ConfigurationProperties} bean (an exception to ADR 0007,
 * recorded in ADR 0051). The entries are checked while they bind ({@link CategoryProperties}); the
 * beans themselves are built from the bound beans as usual (the shared settings {@code
 * helper2.sources.discovery} and {@code helper2.scout.discovery}).
 */
class DiscoveryCollectorRegistrar implements BeanRegistrar {

  /** The settings block every discovery collector shares: base URL, token, timeouts. */
  static final String SETTINGS = "discovery";

  /** Every category, resolved against the shared discovery settings once. */
  record Categories(List<DiscoveryCategory> all) {
    DiscoveryCategory get(String id) {
      return all.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
  }

  @Override
  public void register(BeanRegistry registry, Environment env) {
    CategoryProperties categories =
        Binder.get(env)
            .bind("helper2", Bindable.of(CategoryProperties.class))
            .orElseGet(() -> new CategoryProperties(List.of()));
    registry.registerBean(
        "discoveryCategories",
        Categories.class,
        spec ->
            spec.supplier(ctx ->
                new Categories(categories.resolve(ctx.bean(CollectorProperties.Discovery.class)))));
    for (String id : categories.ids()) {
      registry.registerBean(
          "discovery-" + id,
          TavilyDiscoveryCollector.class,
          spec -> spec.supplier(ctx -> collector(ctx, id)));
    }
  }

  private static TavilyDiscoveryCollector collector(BeanRegistry.SupplierContext ctx, String id) {
    SourceSettings s = ctx.bean(SourcesConfiguration.SourcesProperties.class).get(SETTINGS);
    CollectorProperties.Discovery d = ctx.bean(CollectorProperties.Discovery.class);
    Categories categories = ctx.bean(Categories.class);
    return new TavilyDiscoveryCollector(
        s,
        SourcesConfiguration.collectorClient(s, ctx.bean(ObservationRegistry.class)),
        ctx.bean(Clock.class),
        ctx.bean(Resilience.class),
        new TavilyDiscoveryCollector.Search(d.searchDepth(), d.maxResults()),
        categories.get(id),
        categories.all(),
        ctx.bean(ScoutTavilyCredits.class),
        ctx.bean(DiscoveryHostStore.class));
  }
}
