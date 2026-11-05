package ai.helper2.guide.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The guide's settings bind from application.yaml, and H2_ variables reach them. */
class GuidePropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(GuideProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c ->
        assertThat(c.getBean(GuideProperties.class))
            .isEqualTo(
                new GuideProperties(
                    600,
                    30,
                    30,
                    Duration.ofSeconds(90),
                    Duration.ofSeconds(15),
                    Duration.ofHours(24),
                    null,
                    24,
                    8192,
                    new GuideProperties.Thinking(false, false, false))));
  }

  @Test
  void theChooseCapIsTheSameWithTheNebiusProfile() {
    context
        .withPropertyValues("spring.profiles.active=llm-nebius")
        .run(c -> assertThat(c.getBean(GuideProperties.class).chooseMaxTokens()).isEqualTo(8192));
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties(
            "H2_GUIDE_SIMILAR_THRESHOLD=0.96",
            "H2_GUIDE_CLIENT_DAILY_QUESTIONS=5",
            "H2_NEBIUS_CHOOSE_MAX_TOKENS=4096",
            "H2_GUIDE_THINK_VERIFY=true")
        .run(c -> {
          GuideProperties guide = c.getBean(GuideProperties.class);
          assertThat(guide.similarThreshold()).isEqualTo(0.96);
          assertThat(guide.clientDailyQuestions()).isEqualTo(5);
          assertThat(guide.clientDailyFreeAnswers())
              .as("free answers default to the daily questions")
              .isEqualTo(5);
          assertThat(guide.chooseMaxTokens()).isEqualTo(4096);
          assertThat(guide.thinking().verify()).isTrue();
        });
  }

  @Test
  void theFreeAnswersHaveTheirOwnVariable() {
    context
        .withSystemProperties(
            "H2_GUIDE_CLIENT_DAILY_QUESTIONS=5", "H2_GUIDE_CLIENT_DAILY_FREE_ANSWERS=2")
        .run(c ->
            assertThat(c.getBean(GuideProperties.class).clientDailyFreeAnswers()).isEqualTo(2));
  }

  @Test
  void aThresholdThatIsNotACosineStopsStartup() {
    context
        .withSystemProperties("H2_GUIDE_SIMILAR_THRESHOLD=1.5")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("similarThreshold");
        });
  }
}
