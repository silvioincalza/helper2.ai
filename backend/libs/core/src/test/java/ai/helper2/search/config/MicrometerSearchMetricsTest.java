package ai.helper2.search.config;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.search.ServedFrom;
import ai.helper2.search.SkipReason;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceOutcome;
import ai.helper2.search.SourceStatus;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MicrometerSearchMetricsTest {

  @Test
  void countsSearchesWithAndWithoutResults() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerSearchMetrics metrics = new MicrometerSearchMetrics(registry, List.of());

    metrics.searchCompleted(false, true, Duration.ofMillis(10));
    metrics.searchCompleted(false, false, Duration.ofMillis(10));
    metrics.searchCompleted(true, false, Duration.ofMillis(10));

    assertThat(registry.get("helper2.search.results").tag("empty", "true").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("helper2.search.results").tag("empty", "false").counter().count())
        .isEqualTo(2);
    assertThat(registry.get("helper2.search.results").counter().getId().getTags()).hasSize(1);
  }

  @Test
  void timesEachLiveCallBySourceAndOutcome() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerSearchMetrics metrics = new MicrometerSearchMetrics(registry, List.of());

    metrics.sourceCompleted(
        status("arxiv", SourceOutcome.SUCCEEDED, ServedFrom.LIVE, Duration.ofMillis(120)));
    metrics.sourceCompleted(
        status("arxiv", SourceOutcome.TIMED_OUT, ServedFrom.NONE, Duration.ofMillis(2500)));
    metrics.sourceCompleted(
        status("arxiv", SourceOutcome.FAILED, ServedFrom.STALE_CACHE, Duration.ofMillis(300)));

    assertThat(latency(registry, "arxiv", "SUCCEEDED").totalTime(MILLISECONDS)).isEqualTo(120);
    assertThat(latency(registry, "arxiv", "TIMED_OUT").totalTime(MILLISECONDS)).isEqualTo(2500);
    assertThat(latency(registry, "arxiv", "FAILED").count()).isEqualTo(1);
    assertThat(latency(registry, "arxiv", "SUCCEEDED").getId().getTags())
        .extracting(tag -> tag.getKey())
        .containsExactlyInAnyOrder("source", "outcome");
  }

  @Test
  void publishesHistogramBucketsForPercentilesAcrossPods() {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    MicrometerSearchMetrics metrics = new MicrometerSearchMetrics(registry, List.of());

    metrics.sourceCompleted(
        status("github", SourceOutcome.SUCCEEDED, ServedFrom.LIVE, Duration.ofMillis(80)));

    assertThat(registry.scrape().lines())
        .anyMatch(line ->
            line.startsWith("helper2_source_latency_seconds_bucket{")
                && line.contains("outcome=\"SUCCEEDED\"")
                && line.contains("source=\"github\""));
  }

  @Test
  void aSkippedSourceOrAFreshCacheHitRecordsNoLatency() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerSearchMetrics metrics = new MicrometerSearchMetrics(registry, List.of());

    metrics.sourceCompleted(
        new SourceStatus(
            descriptor("live-web"),
            SourceOutcome.SKIPPED,
            ServedFrom.NONE,
            0,
            Duration.ofMillis(5),
            NOW,
            null,
            "Paused",
            SkipReason.CIRCUIT_OPEN));
    metrics.sourceCompleted(
        status("npm", SourceOutcome.SUCCEEDED, ServedFrom.CACHE, Duration.ZERO));

    assertThat(registry.find("helper2.source.latency").timers()).isEmpty();
    assertThat(registry.get("helper2.source.requests").counters()).hasSize(2);
  }

  private static Timer latency(SimpleMeterRegistry registry, String source, String outcome) {
    return registry
        .get("helper2.source.latency")
        .tag("source", source)
        .tag("outcome", outcome)
        .timer();
  }

  private static SourceStatus status(
      String source, SourceOutcome outcome, ServedFrom servedFrom, Duration latency) {
    return new SourceStatus(
        descriptor(source), outcome, servedFrom, 1, latency, NOW, null, null, null);
  }

  private static SourceDescriptor descriptor(String id) {
    return new SourceDescriptor(
        id,
        id,
        id,
        true,
        SourceMode.LIVE,
        URI.create("https://" + id + ".example"),
        null,
        null,
        Set.of(ResourceType.LIBRARY),
        Duration.ofSeconds(3),
        Duration.ofMinutes(10));
  }

  private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
}
