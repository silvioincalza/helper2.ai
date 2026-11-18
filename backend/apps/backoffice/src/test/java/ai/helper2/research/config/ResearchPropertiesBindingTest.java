package ai.helper2.research.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ResearchPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ResearchProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      ResearchProperties p = c.getBean(ResearchProperties.class);
      assertThat(p.dailyModelCalls()).isEqualTo(3000);
      assertThat(p.maxStreams()).isEqualTo(8);
      assertThat(p.checkUrlDeadline()).isEqualTo(Duration.ofSeconds(20));
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "13"})
  void fewerDailyModelCallsThanOneRunTakesStopsTheStart(String calls) {
    context
        .withSystemProperties("H2_RESEARCH_DAILY_MODEL_CALLS=" + calls)
        .run(c -> assertThat(c).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "14"})
  void noCallsOrEnoughForOneRunStarts(String calls) {
    context
        .withSystemProperties("H2_RESEARCH_DAILY_MODEL_CALLS=" + calls)
        .run(c -> assertThat(c).hasNotFailed());
  }
}
