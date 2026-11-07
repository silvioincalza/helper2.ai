package ai.helper2.search.config;

import ai.helper2.search.Asker;
import ai.helper2.search.QuotaTier;
import ai.helper2.search.SearchMetrics;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceOutcome;
import ai.helper2.search.SourceStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;

/**
 * Metrics: per-source outcome/latency, cache events, partial-result and zero-result frequency. No
 * query text. {@code helper2.source.latency{source,outcome}} has histogram buckets, so p50/p95/p99
 * per source can be taken over all pods.
 */
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
            "helper2.source.requests",
            "source",
            status.getSource().getId(),
            "outcome",
            status.getOutcome().name(),
            "served_from",
            status.getServedFrom().name())
        .increment();
    if (reachedTheSource(status)) {
      Timer.builder("helper2.source.latency")
          .tag("source", status.getSource().getId())
          .tag("outcome", status.getOutcome().name())
          .publishPercentileHistogram()
          .register(registry)
          .record(status.getLatency());
    }
    delegates.forEach(d -> d.sourceCompleted(status));
  }

  /**
   * A live call was made: a skipped source and a fresh cache hit have no latency of their own, so
   * they stay out of {@code helper2.source.latency}.
   */
  private static boolean reachedTheSource(SourceStatus status) {
    return status.getLatency() != null
        && status.getOutcome() != SourceOutcome.SKIPPED
        && status.getServedFrom() != ServedFrom.CACHE;
  }

  @Override
  public void cacheEvent(String event) {
    registry.counter("helper2.cache.events", "event", event).increment();
  }

  @Override
  public void searchCompleted(boolean partial, boolean empty, Duration latency) {
    registry.counter("helper2.search.requests", "partial", Boolean.toString(partial)).increment();
    // Zero-result rate: a count only, never the query (Privacy page).
    registry.counter("helper2.search.results", "empty", Boolean.toString(empty)).increment();
    Timer.builder("helper2.search.latency")
        .publishPercentiles(0.5, 0.95)
        .register(registry)
        .record(latency);
  }

  /**
   * {@code helper2.live-web.credits{feature,tier}}: one per live call of the metered source;
   * internal calls are {@code none}.
   */
  @Override
  public void creditSpent(String sourceId, Asker asker, QuotaTier tier) {
    registry
        .counter(
            "helper2." + sourceId + ".credits",
            "feature",
            asker == null ? "none" : asker.getFeature().name().toLowerCase(java.util.Locale.ROOT),
            "tier",
            tier == null ? "none" : tier.name().toLowerCase(java.util.Locale.ROOT))
        .increment();
  }
}
