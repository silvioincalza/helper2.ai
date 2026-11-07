package ai.helper2.sources.config;

import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.search.DescribedSource;
import ai.helper2.search.SourceDescriptor;
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

/**
 * The Scout's collectors as the public site lists them (GET /api/v1/sources, the Home radar): their
 * descriptors only. The collectors run in the back office (ADR 0032); the descriptors come from the
 * same library factories and settings there and here, so both apps state the same sources and write
 * the same {@code source} rows. Each is a bean of its own, as the collectors are in the back
 * office: the search module lists every {@link DescribedSource}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  CollectorProperties.LiveWebScout.class,
  CollectorProperties.Courses.class,
  CollectorProperties.LinkedIn.class,
  CollectorProperties.LiveWebEvents.class,
  CollectorProperties.Discovery.class,
  CollectorProperties.HfProviderOrgs.class
})
@Profile("!demo")
@Import(ScoutSourceDescriptors.Discovery.class)
class ScoutSourceDescriptors {

  /** A source the Scout reads and the public app only describes. */
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

  /** On with a model to read the pages, as in the back office: the same LLM settings. */
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

  /**
   * One discovery category per entry of {@code helper2.categories} (ADR 0022), named as in the back
   * office ({@code discovery-<id>}), read before any bean exists as the back office's registrar
   * does.
   */
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
}
