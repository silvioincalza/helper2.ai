package ai.helper2.guide.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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
                    5,
                    Duration.ofSeconds(90),
                    Duration.ofSeconds(40),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(15),
                    Duration.ofHours(24),
                    null,
                    24,
                    8192,
                    ModelStream.OFF,
                    new GuideProperties.Thinking(0, 0, 0, 2048),
                    new GuideProperties.Fairness(300, 2, 12))));
  }

  @Test
  void aDeadlineWithoutRoomForTwoModelCallsStopsTheStart() {
    context
        .withSystemProperties("H2_GUIDE_QUESTION_DEADLINE=PT60S", "H2_LLM_TIMEOUT=40s")
        .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void aModelTimeoutShorterThanTheShortestCallAndTheMarginStopsTheStart() {
    context
        .withSystemProperties("H2_LLM_TIMEOUT=11s")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .hasMessageContaining("shortestCallInsideOneModelCall");
        });
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
            "H2_GUIDE_THINK_VERIFY_TOKENS=512")
        .run(c -> {
          GuideProperties guide = c.getBean(GuideProperties.class);
          assertThat(guide.similarThreshold()).isEqualTo(0.96);
          assertThat(guide.clientDailyQuestions()).isEqualTo(5);
          assertThat(guide.clientDailyFreeAnswers())
              .as("free answers default to the daily questions")
              .isEqualTo(5);
          assertThat(guide.chooseMaxTokens()).isEqualTo(4096);
          assertThat(guide.thinking().verifyTokens()).isEqualTo(512);
        });
  }

  @Test
  void theModelStreamIsAnEnumSetByItsH2Variable() {
    context
        .withSystemProperties("H2_GUIDE_MODEL_STREAM=ON")
        .run(c ->
            assertThat(c.getBean(GuideProperties.class).modelStream()).isEqualTo(ModelStream.ON));
  }

  @Test
  void anUnknownModelStreamStopsStartup() {
    context
        .withSystemProperties("H2_GUIDE_MODEL_STREAM=SOMETIMES")
        .run(c -> assertThat(c).hasFailed());
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

  @ParameterizedTest(name = "stream {0}, plan {1}, choose {2}, verify {3}, browse {4}: {5}")
  @CsvSource({
    "OFF, 0, 0, 0, 0, false",
    "OFF, 2048, 2048, 2048, 2048, false",
    "ON, 0, 0, 0, 0, false",
    "ON, 2048, 0, 0, 0, true",
    "ON, 0, 2048, 0, 0, true",
    "ON, 0, 0, 2048, 0, true",
    "ON, 2048, 2048, 2048, 2048, true",
    "ON, 0, 0, 0, 2048, false",
    "OFF, 0, 0, 2048, 0, false",
  })
  void aQuestionNobodyWaitsForStopsOnlyWithTheModelStreamOnAndAStepThatThinks(
      String stream, int plan, int choose, int verify, int browse, boolean stops) {
    context
        .withSystemProperties(
            "H2_GUIDE_MODEL_STREAM=" + stream,
            "H2_GUIDE_THINK_PLAN_TOKENS=" + plan,
            "H2_GUIDE_THINK_CHOOSE_TOKENS=" + choose,
            "H2_GUIDE_THINK_VERIFY_TOKENS=" + verify,
            "H2_GUIDE_THINK_BROWSE_TOKENS=" + browse)
        .run(c ->
            assertThat(c.getBean(GuideProperties.class).stopsAQuestionNobodyWaitsFor())
                .isEqualTo(stops));
  }

  @Test
  void withTheDefaultsAQuestionNobodyWaitsForRunsOn() {
    context.run(
        c -> assertThat(c.getBean(GuideProperties.class).stopsAQuestionNobodyWaitsFor()).isFalse());
  }
}
