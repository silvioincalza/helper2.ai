package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

class ModelCallMetricsTest {

  final MeterRegistry meters = new SimpleMeterRegistry();
  final ModelCallMetrics metrics = new ModelCallMetrics(ObservationRegistry.NOOP, meters);

  double count(String meter, String type) {
    Counter counter = meters.find(meter).tags("step", "choose", "type", type).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void reportedTokensAreCounted() {
    metrics.tokens("choose", "v1", 120, 45);

    assertThat(count(ModelCallMetrics.TOKENS, "prompt")).isEqualTo(120.0);
    assertThat(count(ModelCallMetrics.TOKENS, "completion")).isEqualTo(45.0);
    assertThat(count(ModelCallMetrics.TOKENS_UNKNOWN, "completion")).isZero();
  }

  @Test
  void aCountTheProviderDidNotReportIsOneUnknownCallAndNoTokens() {
    metrics.tokens("choose", "v1", 120, null);
    metrics.tokens("choose", "v1", 120, 0);

    assertThat(count(ModelCallMetrics.TOKENS, "prompt")).isEqualTo(240.0);
    assertThat(count(ModelCallMetrics.TOKENS, "completion")).isZero();
    assertThat(count(ModelCallMetrics.TOKENS_UNKNOWN, "completion")).isEqualTo(2.0);
    assertThat(count(ModelCallMetrics.TOKENS_UNKNOWN, "prompt")).isZero();
  }

  @Test
  void aThinkingFallbackSaysWhyTheSecondCallRan() {
    metrics.thinkingFallback("choose", ModelCallMetrics.FallbackReason.TIME);

    assertThat(
            meters
                .find(ModelCallMetrics.THINKING_FALLBACK)
                .tags("step", "choose", "reason", "time")
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
