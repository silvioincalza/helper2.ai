package ai.helper2.sources.config.scout;

import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.sources.config.CollectorProperties;
import ai.helper2.sources.config.FeedProperties;
import ai.helper2.sources.config.SourcesProperties;
import ai.helper2.sources.feeds.NewsFeedCollector;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

class NewsFeedCollectorRegistrar implements BeanRegistrar {
  static final String SETTINGS = "news-feeds";

  @Override
  public void register(BeanRegistry registry, Environment env) {
    FeedProperties feeds = feeds(env);
    for (String id : feeds.ids()) {
      FeedProperties.Feed feed = feeds.get(id);
      registry.registerBean(
          feed.sourceId(),
          NewsFeedCollector.class,
          spec -> spec.supplier(ctx -> collector(ctx, feed)));
    }
  }

  static FeedProperties feeds(Environment env) {
    return Binder.get(env)
        .bind("helper2", Bindable.of(FeedProperties.class))
        .orElseGet(() -> new FeedProperties(List.of()));
  }

  private static NewsFeedCollector collector(
      BeanRegistry.SupplierContext ctx, FeedProperties.Feed feed) {
    SourceSettings s = ctx.bean(SourcesProperties.class).get(SETTINGS);
    return new NewsFeedCollector(
        s,
        feed,
        CollectorsConfiguration.collectorClient(
            s, ctx.bean(ObservationRegistry.class), ctx.bean(HttpClientProperties.class)),
        ctx.bean(Clock.class),
        ctx.bean(Resilience.class),
        new RunInterval(ctx.bean(CollectorProperties.NewsFeeds.class).interval()));
  }
}
