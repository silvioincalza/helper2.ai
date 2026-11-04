package ai.helper2.search.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The zero-result rate is a count with one tag: no query text reaches the metrics.
 */
class MicrometerSearchMetricsTest {

    @Test
    void countsSearchesWithAndWithoutResults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSearchMetrics metrics = new MicrometerSearchMetrics(registry, List.of());

        metrics.searchCompleted(false, true, Duration.ofMillis(10));
        metrics.searchCompleted(false, false, Duration.ofMillis(10));
        metrics.searchCompleted(true, false, Duration.ofMillis(10));

        assertThat(registry.get("helper2.search.results").tag("empty", "true").counter().count()).isEqualTo(1);
        assertThat(registry.get("helper2.search.results").tag("empty", "false").counter().count()).isEqualTo(2);
        assertThat(registry.get("helper2.search.results").counter().getId().getTags()).hasSize(1);
    }
}
