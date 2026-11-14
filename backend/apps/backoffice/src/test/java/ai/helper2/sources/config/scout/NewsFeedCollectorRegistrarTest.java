package ai.helper2.sources.config.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.config.CollectorProperties;
import ai.helper2.sources.config.SourcesProperties;
import ai.helper2.sources.feeds.NewsFeedCollector;
import ai.helper2.testing.ConfigBinding;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class NewsFeedCollectorRegistrarTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    SourcesProperties.class,
    CollectorProperties.NewsFeeds.class,
    HttpClientProperties.class
  })
  @Import(NewsFeedCollectorRegistrar.class)
  static class Registration {
    @Bean
    Clock clock() {
      return SourceFixtures.CLOCK;
    }

    @Bean
    Resilience resilience() {
      return Resilience.NONE;
    }

    @Bean
    ObservationRegistry observations() {
      return ObservationRegistry.NOOP;
    }
  }

  final ApplicationContextRunner context = ConfigBinding.of(Registration.class);

  @Test
  void everyFeedOfTheYamlIsItsOwnCollectorOnByDefault() {
    context.run(c -> {
      assertThat(c).hasNotFailed();
      Map<String, NewsFeedCollector> collectors = c.getBeansOfType(NewsFeedCollector.class);
      assertThat(collectors)
          .containsOnlyKeys(
              "feed-openai",
              "feed-google-deepmind",
              "feed-google-research",
              "feed-google-ai",
              "feed-hugging-face",
              "feed-mistral",
              "feed-nvidia",
              "feed-aws-ml");
      for (var e : collectors.entrySet()) {
        var descriptor = e.getValue().getDescriptor();
        assertThat(descriptor.getId()).isEqualTo(e.getKey()).matches("^[a-z0-9-]{2,40}$");
        assertThat(descriptor.getResourceTypes()).containsExactly(ResourceType.NEWS);
        assertThat(descriptor.getEnabled()).as(e.getKey()).isTrue();
      }
    });
  }

  @Test
  void switchingTheFeedsOffDisablesEveryFeed() {
    context
        .withPropertyValues("helper2.sources.news-feeds.enabled=false")
        .run(c ->
            assertThat(c.getBeansOfType(NewsFeedCollector.class).values())
                .allSatisfy(f -> assertThat(f.getDescriptor().getEnabled()).isFalse()));
  }

  @Test
  void oneFeedCanBeSwitchedOff() {
    context
        .withPropertyValues(
            concat(
                new String[] {"helper2.sources.news-feeds.enabled=true"},
                feed(0, "vendor", "https://vendor.example/rss", "Vendor"),
                feed(1, "other", "https://other.example/rss", "Other"),
                new String[] {"helper2.feeds[1].enabled=false"}))
        .run(c -> {
          assertThat(c.getBean("feed-vendor", NewsFeedCollector.class).getDescriptor().getEnabled())
              .isTrue();
          assertThat(c.getBean("feed-other", NewsFeedCollector.class).getDescriptor().getEnabled())
              .isFalse();
        });
  }

  @Test
  void aValidFeedStarts() {
    context
        .withPropertyValues(feed(0, "vendor", "https://vendor.example/rss", "Vendor"))
        .run(c ->
            assertThat(c.getBeansOfType(NewsFeedCollector.class)).containsOnlyKeys("feed-vendor"));
  }

  @Test
  void aFeedThatIsNotHttpsStopsStartup() {
    fails(feed(0, "vendor", "http://vendor.example/rss", "Vendor"));
  }

  @Test
  void anIdThatIsNotATagOrTooLongStopsStartup() {
    fails(feed(0, "Vendor", "https://vendor.example/rss", "Vendor"));
    fails(feed(0, "a".repeat(36), "https://vendor.example/rss", "Vendor"));
  }

  @Test
  void aShortNameOverTwelveCharactersStopsStartup() {
    fails(feed(0, "vendor", "https://vendor.example/rss", "Vendor news feed"));
  }

  @Test
  void twoFeedsWithOneIdStopStartup() {
    context
        .withPropertyValues(
            concat(
                feed(0, "vendor", "https://vendor.example/rss", "Vendor"),
                feed(1, "vendor", "https://vendor.example/atom", "Vendor")))
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure()).rootCause().hasMessageContaining("duplicate id vendor");
        });
  }

  private void fails(String[] entry) {
    context.withPropertyValues(entry).run(c -> assertThat(c).hasFailed());
  }

  private static String[] feed(int i, String id, String url, String shortName) {
    String p = "helper2.feeds[" + i + "].";
    return new String[] {
      p + "id=" + id,
      p + "publisher=" + shortName,
      p + "short-name=" + shortName,
      p + "url=" + url,
      p + "homepage=https://vendor.example/"
    };
  }

  private static String[] concat(String[]... parts) {
    return java.util.Arrays.stream(parts).flatMap(java.util.Arrays::stream).toArray(String[]::new);
  }
}
