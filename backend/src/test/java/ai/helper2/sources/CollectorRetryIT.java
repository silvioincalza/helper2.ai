package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.resilience.Resilience4jPolicies;
import ai.helper2.search.SourceFailure;
import ai.helper2.testing.FixtureServer;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A collector behind its policy over HTTP: a 503 is retried with backoff, a 404 is not. */
class CollectorRetryIT {

    private final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    private McpRegistryCollector collector() {
        RetryRegistry retries = RetryRegistry.ofDefaults();
        retries.retry(McpRegistryCollector.POLICY, RetryConfig.custom().maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(10), 2, 0.5))
            .retryOnException(new TransientSourceFailure()).build());
        Resilience policies = new Resilience4jPolicies(CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(), retries);
        return new McpRegistryCollector(new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP), GitHubSourceTest.CLOCK, policies);
    }

    @Test
    void aServiceUnavailableIsTriedThreeTimes() {
        server.route("/v0.1/servers", new FixtureServer.Reply(503, "{}", Map.of(), 0));

        assertThatThrownBy(() -> collector().collect(null, null, 10)).isInstanceOf(SourceFailure.Unavailable.class);
        assertThat(server.requests).hasSize(3);
    }

    @Test
    void aNotFoundIsNotRetried() {
        server.route("/v0.1/servers", new FixtureServer.Reply(404, "{}", Map.of(), 0));

        assertThatThrownBy(() -> collector().collect(null, null, 10)).isInstanceOf(SourceFailure.Unavailable.class);
        assertThat(server.requests).hasSize(1);
    }
}
