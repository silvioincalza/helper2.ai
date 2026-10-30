package ai.helper2.search.config;

import ai.helper2.search.Asker;
import ai.helper2.search.QuotaTier;
import ai.helper2.search.SearchMetrics;
import ai.helper2.search.SourceStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;

/** Metrics: per-source outcome/latency, cache events, partial-result frequency. No query text. */
class MicrometerSearchMetrics implements SearchMetrics {

    private final MeterRegistry registry;
    private final List<SearchMetrics> delegates;

    MicrometerSearchMetrics(MeterRegistry registry, List<SearchMetrics> delegates) {
        this.registry = registry;
        this.delegates = delegates;
    }

    @Override
    public void sourceCompleted(SourceStatus status) {
        registry.counter("helper2.source.requests", "source", status.getSource().getId(),
            "outcome", status.getOutcome().name(), "served_from", status.getServedFrom().name()).increment();
        if (status.getLatency() != null) {
            Timer.builder("helper2.source.latency").tag("source", status.getSource().getId())
                .publishPercentiles(0.5, 0.95).register(registry).record(status.getLatency());
        }
        delegates.forEach(d -> d.sourceCompleted(status));
    }

    @Override
    public void cacheEvent(String event) {
        registry.counter("helper2.cache.events", "event", event).increment();
    }

    @Override
    public void searchCompleted(boolean partial, Duration latency) {
        registry.counter("helper2.search.requests", "partial", Boolean.toString(partial)).increment();
        Timer.builder("helper2.search.latency").publishPercentiles(0.5, 0.95).register(registry).record(latency);
    }

    /** {@code helper2.tavily.credits{feature,tier}}: one per live call of the metered source; internal calls are {@code none}. */
    @Override
    public void creditSpent(String sourceId, Asker asker, QuotaTier tier) {
        registry.counter("helper2." + sourceId + ".credits",
            "feature", asker == null ? "none" : asker.getFeature().name().toLowerCase(java.util.Locale.ROOT),
            "tier", tier == null ? "none" : tier.name().toLowerCase(java.util.Locale.ROOT)).increment();
    }
}
