package ai.helper2.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ScoutEvidencePropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ScoutEvidenceProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c ->
        assertThat(c.getBean(ScoutEvidenceProperties.class))
            .isEqualTo(new ScoutEvidenceProperties(Duration.ofDays(30), Duration.ofHours(24))));
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties(
            "H2_SCOUT_EVIDENCE_PAGE_READ_TTL=P7D", "H2_SCOUT_EVIDENCE_PAGE_TEXT_TTL=PT6H")
        .run(c ->
            assertThat(c.getBean(ScoutEvidenceProperties.class))
                .isEqualTo(new ScoutEvidenceProperties(Duration.ofDays(7), Duration.ofHours(6))));
  }

  @Test
  void aPageTextTtlOverADayStopsStartup() {
    context
        .withSystemProperties("H2_SCOUT_EVIDENCE_PAGE_TEXT_TTL=P2D")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("pageTextTtl");
        });
  }

  @Test
  void aPageTextTtlOfADayStarts() {
    context
        .withSystemProperties("H2_SCOUT_EVIDENCE_PAGE_TEXT_TTL=P1D")
        .run(c ->
            assertThat(c.getBean(ScoutEvidenceProperties.class).pageTextTtl())
                .isEqualTo(Duration.ofDays(1)));
  }
}
