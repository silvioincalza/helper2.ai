package ai.searchavo.sources.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.platform.resilience.Resilience4jPolicies;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.sources.support.RunInterval;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.sources.support.TransientSourceFailure;
import ai.searchavo.testing.FixtureServer;
import ai.searchavo.testing.TestHttp;
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

class CollectorRetryIT {

  private final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  private McpRegistryCollector collector() {
    return collector(Duration.ofSeconds(2));
  }

  private McpRegistryCollector collector(Duration deadline) {
    RetryRegistry retries = RetryRegistry.ofDefaults();
    retries.retry(
        McpRegistryCollector.POLICY,
        RetryConfig.custom()
            .maxAttempts(3)
            .intervalFunction(
                IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(10), 2, 0.5))
            .retryOnException(new TransientSourceFailure())
            .build());
    Resilience policies =
        new Resilience4jPolicies(
            CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(), retries);
    return new McpRegistryCollector(
        new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ZERO, null),
        TestHttp.restClientBuilder(Duration.ofSeconds(2), deadline, ObservationRegistry.NOOP),
        SourceFixtures.CLOCK,
        policies,
        new RunInterval(Duration.ZERO),
        0);
  }

  @Test
  void aServiceUnavailableIsTriedThreeTimes() {
    server.route("/v0.1/servers", new FixtureServer.Reply(503, "{}", Map.of(), 0));

    assertThatThrownBy(() -> collector().collect(null, null, 10))
        .isInstanceOf(SourceFailure.Unavailable.class);
    assertThat(server.requests).hasSize(3);
  }

  @Test
  void aNotFoundIsNotRetried() {
    server.route("/v0.1/servers", new FixtureServer.Reply(404, "{}", Map.of(), 0));

    assertThatThrownBy(() -> collector().collect(null, null, 10))
        .isInstanceOf(SourceFailure.Unavailable.class);
    assertThat(server.requests).hasSize(1);
  }

  @Test
  void aCallPastItsDeadlineIsRetried() {
    server.route("/v0.1/servers", FixtureServer.Reply.drip("{\"servers\":[],\"metadata\":{}}", 50));

    assertThatThrownBy(() -> collector(Duration.ofMillis(300)).collect(null, null, 10))
        .isInstanceOfSatisfying(
            SourceFailure.Unavailable.class,
            e -> assertThat(e.getMessage()).contains("deadline exceeded"));
    assertThat(server.requests).hasSize(3);
  }
}
