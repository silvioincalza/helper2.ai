package ai.searchavo.sources.config.scout;

import ai.searchavo.platform.config.HttpClientProperties;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.sources.config.CollectorProperties;
import ai.searchavo.sources.config.FeedProperties;
import ai.searchavo.sources.config.SourcesProperties;
import ai.searchavo.sources.feeds.NewsFeedCollector;
import ai.searchavo.sources.support.RunInterval;
import ai.searchavo.sources.support.SourceSettings;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

class NewsFeedCollectorRegistrar implements BeanRegistrar {
  static final String SETTINGS = "news-feeds";

  @Override
  public void register(BeanRegistry registry, Environment env) {
    FeedProperties feeds = FeedProperties.bind(env);
    for (String id : feeds.ids()) {
      FeedProperties.Feed feed = feeds.get(id);
      registry.registerBean(
          feed.sourceId(),
          NewsFeedCollector.class,
          spec -> spec.supplier(ctx -> collector(ctx, feed)));
    }
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
