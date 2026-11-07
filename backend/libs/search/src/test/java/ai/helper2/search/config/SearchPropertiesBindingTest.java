package ai.helper2.search.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The search settings bind from application.yaml, and H2_ variables reach them. */
class SearchPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({SearchProperties.class, SearchRetentionProperties.class})
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      assertThat(c.getBean(SearchProperties.class))
          .isEqualTo(
              new SearchProperties(
                  Duration.ofHours(24),
                  30,
                  Duration.ofMillis(2500),
                  new SearchProperties.Translation(true, 300, Duration.ofHours(24)),
                  new SearchProperties.Web(
                      Duration.ofHours(24),
                      Duration.ofHours(48),
                      new SearchProperties.Quota(
                          new SearchProperties.Limits(3, 3),
                          new SearchProperties.Limits(10, 10),
                          false,
                          null))));
      assertThat(c.getBean(SearchRetentionProperties.class))
          .isEqualTo(new SearchRetentionProperties(Duration.ofDays(7)));
    });
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties(
            "H2_SEARCH_TRANSLATE_TIMEOUT_MS=1200",
            "H2_SEARCH_DAILY_TRANSLATIONS=50",
            "H2_LIVE_WEB_GUEST_SEARCH=7",
            "H2_LIVE_WEB_DAILY_CREDITS=900",
            "H2_LIVE_WEB_REGISTERED_TIER_ENABLED=true",
            "H2_RETENTION_LIVE_WEB_RESULTS=P3D")
        .run(c -> {
          SearchProperties search = c.getBean(SearchProperties.class);
          assertThat(search.translateTimeout())
              .as("a plain number is milliseconds")
              .isEqualTo(Duration.ofMillis(1200));
          assertThat(search.translation().dailyCalls()).isEqualTo(50);
          assertThat(search.web().quota().guest().search()).isEqualTo(7);
          assertThat(search.web().quota().globalDailyCredits()).isEqualTo(900L);
          assertThat(search.web().quota().registeredTierEnabled()).isTrue();
          assertThat(c.getBean(SearchRetentionProperties.class).liveWebResults())
              .isEqualTo(Duration.ofDays(3));
        });
  }

  @Test
  void aNegativeQuotaStopsStartup() {
    context
        .withSystemProperties("H2_LIVE_WEB_GUEST_GUIDE=-1")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("web.quota.guest.guide");
        });
  }
}
