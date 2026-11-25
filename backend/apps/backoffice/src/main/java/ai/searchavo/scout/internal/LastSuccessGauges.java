package ai.searchavo.scout.internal;

import ai.searchavo.scout.LastSuccesses;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class LastSuccessGauges implements MeterBinder {

  private record Snapshot(Instant readAt, Map<String, Instant> bySource) {}

  private final List<String> sources;
  private final LastSuccesses store;
  private final Clock clock;
  private final Duration freshFor;
  private Snapshot snapshot;

  LastSuccessGauges(List<String> sources, LastSuccesses store, Clock clock, Duration freshFor) {
    this.sources = List.copyOf(sources);
    this.store = store;
    this.clock = clock;
    this.freshFor = freshFor;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    sources.forEach(source -> register(source, registry));
  }

  private void register(String source, MeterRegistry registry) {
    Gauge.builder("searchavo.scout.last.success", this, gauges -> gauges.epochSecondOf(source))
        .description("When the source's last Scout run succeeded, from scout_run")
        .tag("source", source)
        .strongReference(true)
        .register(registry);
  }

  double epochSecondOf(String source) {
    Instant succeededAt = current().get(source);
    return succeededAt == null ? Double.NaN : succeededAt.getEpochSecond();
  }

  private synchronized Map<String, Instant> current() {
    Instant now = clock.instant();
    if (snapshot == null || !now.isBefore(snapshot.readAt().plus(freshFor))) {
      snapshot = new Snapshot(now, store.bySource());
    }
    return snapshot.bySource();
  }
}
