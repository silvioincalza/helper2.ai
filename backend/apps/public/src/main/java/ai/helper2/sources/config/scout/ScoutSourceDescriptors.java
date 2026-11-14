package ai.helper2.sources.config.scout;

import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.search.DescribedSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.sources.config.CategoryProperties;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.config.CollectorProperties;
import ai.helper2.sources.config.FeedProperties;
import ai.helper2.sources.config.SourcesProperties;
import ai.helper2.sources.support.SourceSettings;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  CollectorProperties.LiveWebScout.class,
  CollectorProperties.Courses.class,
  CollectorProperties.LinkedIn.class,
  CollectorProperties.LiveWebEvents.class,
  CollectorProperties.Discovery.class,
  CollectorProperties.HfProviderOrgs.class,
  CollectorProperties.GitHubTopicLeaders.class,
  CollectorProperties.GitHubSdks.class
})
@Profile("!demo")
@Import({ScoutSourceDescriptors.Discovery.class, ScoutSourceDescriptors.NewsFeeds.class})
class ScoutSourceDescriptors {
  record Described(SourceDescriptor descriptor) implements DescribedSource {
    @Override
    public SourceDescriptor getDescriptor() {
      return descriptor;
    }
  }

  @Bean
  DescribedSource aiDeadlinesSource(SourcesProperties p) {
    return new Described(
        CollectorDescriptors.aiDeadlines(p.get(CollectorDescriptors.AI_DEADLINES)));
  }

  @Bean
  DescribedSource gitHubAwesomeSource(SourcesProperties p) {
    return new Described(
        CollectorDescriptors.gitHubAwesome(p.get(CollectorDescriptors.GITHUB_AWESOME)));
  }

  @Bean
  DescribedSource gitHubTopicLeadersSource(
      SourcesProperties p, CollectorProperties.GitHubTopicLeaders c) {
    return new Described(
        CollectorDescriptors.gitHubTopicLeaders(
            p.get(CollectorDescriptors.GITHUB_TOPIC_LEADERS), c.topics()));
  }

  @Bean
  DescribedSource gitHubSdksSource(SourcesProperties p, CollectorProperties.GitHubSdks c) {
    return new Described(
        CollectorDescriptors.gitHubSdks(
            p.get(CollectorDescriptors.GITHUB_SDKS), c.languages(), c.orgs()));
  }

  @Bean
  DescribedSource hfProviderOrgsSource(SourcesProperties p, CollectorProperties.HfProviderOrgs c) {
    return new Described(
        CollectorDescriptors.hfProviderOrgs(
            p.get(CollectorDescriptors.HF_PROVIDER_ORGS), c.orgs()));
  }

  @Bean
  DescribedSource hfTrendingSource(SourcesProperties p) {
    return new Described(CollectorDescriptors.hfTrending(p.get(CollectorDescriptors.HF_TRENDING)));
  }

  @Bean
  DescribedSource mcpRegistrySource(SourcesProperties p) {
    return new Described(
        CollectorDescriptors.mcpRegistry(p.get(CollectorDescriptors.MCP_REGISTRY)));
  }

  @Bean
  DescribedSource pypiSource(SourcesProperties p) {
    return new Described(CollectorDescriptors.pypi(p.get(CollectorDescriptors.PYPI)));
  }

  @Bean
  DescribedSource liveWebScoutSource(SourcesProperties p, CollectorProperties.LiveWebScout c) {
    return new Described(
        CollectorDescriptors.liveWebScout(p.get(CollectorDescriptors.LIVE_WEB_SCOUT), c.queries()));
  }

  @Bean
  DescribedSource coursesSource(SourcesProperties p, CollectorProperties.Courses c) {
    return new Described(
        CollectorDescriptors.courses(
            p.get(CollectorDescriptors.COURSES), c.query(), c.platforms()));
  }

  @Bean
  DescribedSource liveWebEventsSource(
      SourcesProperties p,
      CollectorProperties.LiveWebEvents c,
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm) {
    return new Described(
        CollectorDescriptors.liveWebEvents(
            p.get(CollectorDescriptors.LIVE_WEB_EVENTS),
            c.queries(),
            llm.usable(chatModel.getIfAvailable())));
  }

  @Bean
  DescribedSource linkedInSource(SourcesProperties p, CollectorProperties.LinkedIn c) {
    return new Described(
        CollectorDescriptors.linkedIn(p.get(CollectorDescriptors.LINKEDIN), c.queries()));
  }

  static class Discovery implements BeanRegistrar {
    @Override
    public void register(BeanRegistry registry, Environment env) {
      CategoryProperties categories =
          Binder.get(env)
              .bind("helper2", Bindable.of(CategoryProperties.class))
              .orElseGet(() -> new CategoryProperties(List.of()));
      for (String id : categories.ids()) {
        registry.registerBean(
            "discovery-" + id,
            DescribedSource.class,
            spec ->
                spec.supplier(ctx -> {
                  SourceSettings s = ctx.bean(SourcesProperties.class).get("discovery");
                  var category =
                      categories.resolve(ctx.bean(CollectorProperties.Discovery.class)).stream()
                          .filter(c -> c.id().equals(id))
                          .findFirst()
                          .orElseThrow();
                  return new Described(CollectorDescriptors.discovery(s, category));
                }));
      }
    }
  }

  static class NewsFeeds implements BeanRegistrar {
    @Override
    public void register(BeanRegistry registry, Environment env) {
      FeedProperties feeds =
          Binder.get(env)
              .bind("helper2", Bindable.of(FeedProperties.class))
              .orElseGet(() -> new FeedProperties(List.of()));
      for (String id : feeds.ids()) {
        FeedProperties.Feed feed = feeds.get(id);
        registry.registerBean(
            feed.sourceId(),
            DescribedSource.class,
            spec ->
                spec.supplier(ctx ->
                    new Described(
                        CollectorDescriptors.newsFeed(
                            ctx.bean(SourcesProperties.class).get("news-feeds"), feed))));
      }
    }
  }
}
