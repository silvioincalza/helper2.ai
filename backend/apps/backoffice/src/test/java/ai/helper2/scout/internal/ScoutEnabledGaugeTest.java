package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ScoutEnabledGaugeTest {

  private static double enabledGauge(boolean enabled) {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    new ScoutEnabledGauge(enabled).bindTo(meters);
    return meters.get("helper2.scout.enabled").gauge().value();
  }

  @Test
  void anEnvironmentThatRunsTheScoutReportsOne() {
    assertThat(enabledGauge(true)).isEqualTo(1);
  }

  @Test
  void anEnvironmentWithTheScoutOffReportsZero() {
    assertThat(enabledGauge(false)).isZero();
  }
}
