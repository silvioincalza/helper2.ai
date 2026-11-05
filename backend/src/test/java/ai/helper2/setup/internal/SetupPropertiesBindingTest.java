package ai.helper2.setup.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.setup.internal.SetupConfiguration.SetupProperties;
import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The setup guide's settings bind from application.yaml, and H2_SETUP_* variables reach them. */
class SetupPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SetupProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      assertThat(c).hasNotFailed();
      assertThat(c.getBean(SetupProperties.class))
          .isEqualTo(
              new SetupProperties(
                  Duration.ofSeconds(10),
                  "https://cdn.jsdelivr.net",
                  Duration.ofHours(1),
                  32,
                  1000,
                  "",
                  new SetupProperties.Guide(
                      300, false, Duration.ofDays(7), Duration.ofMinutes(10))));
    });
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties(
            "H2_SETUP_HOURLY_FETCHES=5", "H2_SETUP_GUIDE_CACHE_TTL=PT2H", "H2_SETUP_GITHUB_TOKEN=x")
        .run(c -> {
          SetupProperties setup = c.getBean(SetupProperties.class);
          assertThat(setup.hourlyFetches()).isEqualTo(5);
          assertThat(setup.guide().cacheTtl()).isEqualTo(Duration.ofHours(2));
          assertThat(setup.githubToken()).isEqualTo("x");
        });
  }

  @Test
  void toStringNeverPrintsTheToken() {
    context
        .withSystemProperties("H2_SETUP_GITHUB_TOKEN=ghp_secret")
        .run(c ->
            assertThat(c.getBean(SetupProperties.class).toString())
                .doesNotContain("ghp_secret")
                .contains("githubToken=***", "hourlyFetches=1000"));
  }
}
