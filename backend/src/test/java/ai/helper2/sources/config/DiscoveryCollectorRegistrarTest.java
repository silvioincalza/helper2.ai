package ai.helper2.sources.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Collector;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.tavily.DiscoveryCategory;
import ai.helper2.sources.tavily.DiscoveryFamily;
import ai.helper2.sources.tavily.ScoutTavilyCredits;
import ai.helper2.sources.tavily.TavilyDiscoveryCollector;
import ai.helper2.testing.ConfigBinding;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The categories of application.yaml become one discovery collector each (ADR 0051), and a bad
 * entry stops startup with the category it is in.
 */
class DiscoveryCollectorRegistrarTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    SourcesConfiguration.SourcesProperties.class,
    CollectorProperties.Discovery.class
  })
  @Import(DiscoveryCollectorRegistrar.class)
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

    @Bean
    ScoutTavilyCredits credits() {
      return new ScoutTavilyCredits(
          new NoCounter(), new QuotaKeys("", SourceFixtures.CLOCK), 40, new SimpleMeterRegistry());
    }
  }

  /** Nothing counted: the registration never searches. */
  static final class NoCounter implements QuotaCounter {
    @Override
    public Result take(Counter own, int ownLimit, Counter global, long globalLimit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void add(Counter counter, long amount) {}

    @Override
    public void giveBack(Counter counter) {}

    @Override
    public long used(Counter counter) {
      return 0;
    }

    @Override
    public void delete(Collection<String> keys) {}
  }

  final ApplicationContextRunner context = ConfigBinding.of(Registration.class);

  @Test
  void everyCategoryOfTheYamlIsItsOwnCollector() {
    context.run(c -> {
      assertThat(c).hasNotFailed();
      Map<String, TavilyDiscoveryCollector> collectors =
          c.getBeansOfType(TavilyDiscoveryCollector.class);
      assertThat(collectors).hasSize(34);
      assertThat(c.getBeansOfType(Collector.class)).hasSize(34);
      for (var e : collectors.entrySet()) {
        String id = e.getValue().getDescriptor().getId();
        assertThat(id).isEqualTo(e.getKey()).startsWith("discovery-").matches("^[a-z0-9-]{2,40}$");
        assertThat(e.getValue().getDescriptor().getShortName()).hasSizeLessThanOrEqualTo(12);
        assertThat(e.getValue().getDescriptor().getFamily()).isNotNull();
      }
      Map<DiscoveryFamily, Long> perFamily =
          categories(collectors.values()).stream()
              .collect(Collectors.groupingBy(DiscoveryCategory::family, Collectors.counting()));
      assertThat(perFamily)
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  DiscoveryFamily.PROMPTS_AND_SKILLS, 2L,
                  DiscoveryFamily.EVERYDAY_APPS, 10L,
                  DiscoveryFamily.CREATIVE_AND_MEDIA, 4L,
                  DiscoveryFamily.BUSINESS, 6L,
                  DiscoveryFamily.DEVELOPERS, 7L,
                  DiscoveryFamily.HOSTING_AND_INFRA, 3L,
                  DiscoveryFamily.RUN_IT_YOURSELF, 2L));
      assertThat(categories(collectors.values()).stream().mapToInt(d -> d.queries().size()).sum())
          .isEqualTo(39);
    });
  }

  @Test
  void aCategorysOptionsFallBackToTheSharedSettingsAndAddToTheirDomains() {
    context.run(c -> {
      Map<String, DiscoveryCategory> byId =
          categories(c.getBeansOfType(TavilyDiscoveryCollector.class).values()).stream()
              .collect(Collectors.toMap(DiscoveryCategory::id, Function.identity()));
      DiscoveryCategory health = byId.get("health");
      assertThat(health.queries()).hasSize(2);
      assertThat(health.minScore()).isEqualTo(0.5);
      assertThat(health.requireKeyword()).isTrue();
      assertThat(health.indexLists()).isFalse();
      assertThat(health.excludeDomains()).contains("youtube.com", "webmd.com", "nhs.uk");
      DiscoveryCategory coding = byId.get("coding");
      assertThat(coding.minScore()).isEqualTo(0.4);
      assertThat(coding.interval()).isEqualTo(Duration.ofDays(7));
      assertThat(coding.requireKeyword()).isFalse();
      assertThat(coding.indexLists()).isTrue();
      assertThat(coding.type()).isEqualTo(ResourceType.PRODUCT);
      assertThat(byId.get("legal").excludeDomains()).contains("law.cornell.edu");
      assertThat(byId.get("voice-agents").type()).isEqualTo(ResourceType.AGENT);
      assertThat(byId.get("image-generation").words().words())
          .contains("ai art generat", "ai artwork")
          .doesNotContain("ai art");
    });
  }

  @Test
  void theCategoriesStartOnDifferentDaysFourOrFiveADay() {
    context.run(c -> {
      List<TavilyDiscoveryCollector> collectors =
          List.copyOf(c.getBeansOfType(TavilyDiscoveryCollector.class).values());
      int[] perDay = new int[7];
      java.time.Instant monday = java.time.Instant.parse("2026-11-02T03:07:00Z");
      for (int day = 0; day < 7; day++) {
        java.time.Instant at = monday.plus(Duration.ofDays(day));
        for (TavilyDiscoveryCollector collector : collectors) {
          if (collector.interval().isDue(null, at)) {
            perDay[day]++;
          }
        }
      }
      assertThat(Arrays.stream(perDay).sum()).isEqualTo(34);
      assertThat(Arrays.stream(perDay).allMatch(n -> n == 4 || n == 5))
          .as(Arrays.toString(perDay))
          .isTrue();
    });
  }

  @Test
  void anUnknownFamilyStopsStartup() {
    fails(entry("coding", "nowhere", "Coding", "PRODUCT"));
  }

  @Test
  void anIdThatIsNotATagOrTooLongStopsStartup() {
    fails(entry("Coding", "developers", "Coding", "PRODUCT"));
    fails(entry("a".repeat(31), "developers", "Coding", "PRODUCT"));
  }

  @Test
  void aShortNameOverTwelveCharactersStopsStartup() {
    fails(entry("coding", "developers", "Coding agents", "PRODUCT"));
  }

  @Test
  void aTypeAProductPageCannotHaveStopsStartup() {
    fails(entry("news", "developers", "News", "NEWS"));
    fails(entry("widgets", "developers", "Widgets", "WIDGET"));
  }

  @Test
  void twoCategoriesWithOneIdStopStartup() {
    context
        .withPropertyValues(
            concat(
                entry("coding", "developers", "Coding", "PRODUCT", 0),
                entry("coding", "developers", "Coding", "PRODUCT", 1)))
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure()).rootCause().hasMessageContaining("duplicate id coding");
        });
  }

  private void fails(String[] entry) {
    context.withPropertyValues(entry).run(c -> assertThat(c).hasFailed());
  }

  private static String[] entry(String id, String family, String shortName, String type) {
    return entry(id, family, shortName, type, 0);
  }

  private static String[] entry(String id, String family, String shortName, String type, int i) {
    String p = "helper2.categories[" + i + "].";
    return new String[] {
      p + "id=" + id,
      p + "family=" + family,
      p + "short-name=" + shortName,
      p + "name=" + shortName,
      p + "words[0]=word",
      p + "discovery.type=" + type,
      p + "discovery.queries[0]=a query"
    };
  }

  private static String[] concat(String[] a, String[] b) {
    String[] both = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, both, a.length, b.length);
    return both;
  }

  private static List<DiscoveryCategory> categories(Collection<TavilyDiscoveryCollector> all) {
    return all.stream().map(TavilyDiscoveryCollector::category).toList();
  }
}
