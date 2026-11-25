package ai.searchavo.search.config;

import ai.searchavo.search.Asker;
import ai.searchavo.search.Refusal;
import ai.searchavo.search.SearchMetrics;
import ai.searchavo.search.ServedFrom;
import ai.searchavo.search.SourceOutcome;
import ai.searchavo.search.SourceStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;

class MicrometerSearchMetrics implements SearchMetrics {

  private final MeterRegistry registry;
  private final List<SearchMetrics> delegates;

  MicrometerSearchMetrics(MeterRegistry registry, List<SearchMetrics> delegates) {
    this.registry = registry;
    this.delegates = delegates;
  }

  @Override
  public void sourceCompleted(SourceStatus status) {
    registry
        .counter(
            "searchavo.source.requests",
            "source",
            status.getSource().getId(),
            "outcome",
            status.getOutcome().name(),
            "served_from",
            status.getServedFrom().name())
        .increment();
    if (reachedTheSource(status)) {
      Timer.builder("searchavo.source.latency")
          .tag("source", status.getSource().getId())
          .tag("outcome", status.getOutcome().name())
          .publishPercentileHistogram()
          .register(registry)
          .record(status.getLatency());
    }
    delegates.forEach(d -> d.sourceCompleted(status));
  }

  private static boolean reachedTheSource(SourceStatus status) {
    return status.getLatency() != null
        && status.getOutcome() != SourceOutcome.SKIPPED
        && status.getServedFrom() != ServedFrom.CACHE;
  }

  @Override
  public void hitRefused(String sourceId, Refusal refusal) {
    registry
        .counter(
            "searchavo.source.items.dropped", "source", sourceId, "reason", refusal.getReason())
        .increment();
  }

  @Override
  public void cacheEvent(String event) {
    registry.counter("searchavo.cache.events", "event", event).increment();
  }

  @Override
  public void searchCompleted(boolean partial, boolean empty, Duration latency) {
    registry.counter("searchavo.search.requests", "partial", Boolean.toString(partial)).increment();
    registry.counter("searchavo.search.results", "empty", Boolean.toString(empty)).increment();
    Timer.builder("searchavo.search.latency")
        .publishPercentiles(0.5, 0.95)
        .register(registry)
        .record(latency);
  }

  @Override
  public void creditSpent(String sourceId, Asker asker) {
    registry
        .counter(
            "searchavo." + sourceId + ".credits",
            "feature",
            asker == null ? "none" : asker.getFeature().name().toLowerCase(java.util.Locale.ROOT))
        .increment();
  }
}
