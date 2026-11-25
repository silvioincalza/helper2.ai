package ai.searchavo.scout.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

final class ScoutEnabledGauge implements MeterBinder {

  private final boolean enabled;

  ScoutEnabledGauge(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("searchavo.scout.enabled", () -> enabled ? 1 : 0)
        .description("1 when the Scout runs on its schedule on this environment, 0 when it is off")
        .strongReference(true)
        .register(registry);
  }
}
