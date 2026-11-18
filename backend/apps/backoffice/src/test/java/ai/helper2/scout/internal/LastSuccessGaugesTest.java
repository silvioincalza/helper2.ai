package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LastSuccessGaugesTest {

  private static final Instant NOW = Instant.parse("2026-11-17T03:14:22Z");

  private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  private final AtomicInteger reads = new AtomicInteger();
  private final AtomicReference<Map<String, Instant>> rows =
      new AtomicReference<>(Map.of("hf-trending", NOW));

  private SimpleMeterRegistry bound() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    new LastSuccessGauges(
            List.of("hf-trending", "pypi"),
            () -> {
              reads.incrementAndGet();
              return rows.get();
            },
            clock,
            Duration.ofSeconds(30))
        .bindTo(meters);
    return meters;
  }

  private static double gauge(SimpleMeterRegistry meters, String source) {
    return meters.get("helper2.scout.last.success").tag("source", source).gauge().value();
  }

  @Test
  void everyCollectorHasAGaugeFromTheStart_NaNUntilItsFirstSuccess() {
    SimpleMeterRegistry meters = bound();

    assertThat(gauge(meters, "hf-trending")).isEqualTo(NOW.getEpochSecond());
    assertThat(gauge(meters, "pypi")).isNaN();
  }

  @Test
  void oneScrapeReadsTheStoreOnce_andAgainOnlyAfterThirtySeconds() {
    SimpleMeterRegistry meters = bound();
    gauge(meters, "hf-trending");
    gauge(meters, "pypi");
    assertThat(reads).hasValue(1);

    Instant later = NOW.plusSeconds(86_400);
    rows.set(Map.of("hf-trending", later));
    now.set(NOW.plusSeconds(29));
    assertThat(gauge(meters, "hf-trending")).isEqualTo(NOW.getEpochSecond());

    now.set(NOW.plusSeconds(30));
    assertThat(gauge(meters, "hf-trending")).isEqualTo(later.getEpochSecond());
    assertThat(reads).hasValue(2);
  }
}
