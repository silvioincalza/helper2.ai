package ai.helper2.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.search.SourceFailure;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The policies of application.yaml as the app loads them: every name in place, the settings ADR 0033 states, meters bound. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResiliencePoliciesConfigIT {

    static final List<String> SOURCES = List.of("github", "hf-models", "hf-spaces", "npm", "arxiv", "tavily",
        "helper2-index", "helper2-semantic", "curated");
    static final List<String> COLLECTORS = List.of("hf-trending", "tavily-scout", "course-scout", "github-awesome",
        "hf-provider-orgs", "ai-deadlines", "mcp-registry");

    @Autowired
    Resilience resilience;

    @Autowired
    CircuitBreakerRegistry circuits;

    @Autowired
    BulkheadRegistry bulkheads;

    @Autowired
    RetryRegistry retries;

    @Autowired
    MeterRegistry meters;

    @Test
    void everySourceHasACircuitAndABulkheadButNoRetry() {
        for (String id : SOURCES) {
            String policy = "source-" + id;
            resilience.require(policy);
            assertThat(circuits.find(policy)).as(policy).isPresent();
            assertThat(bulkheads.find(policy)).as(policy).isPresent();
            assertThat(retries.find(policy)).as(policy + " never retries").isEmpty();
        }
        var github = circuits.find("source-github").orElseThrow().getCircuitBreakerConfig();
        assertThat(github.getSlidingWindowSize()).isEqualTo(20);
        assertThat(github.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(github.getIgnoreExceptionPredicate().test(new SourceFailure.RateLimited(null))).isTrue();
        assertThat(bulkheads.find("source-github").orElseThrow().getBulkheadConfig().getMaxWaitDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    void everyCollectorRetriesOnlyTransientFailures() {
        for (String id : COLLECTORS) {
            String policy = "collector-" + id;
            assertThat(circuits.find(policy)).as(policy).isPresent();
            var retry = retries.find(policy).orElseThrow().getRetryConfig();
            assertThat(retry.getMaxAttempts()).isEqualTo(3);
            assertThat(retry.getExceptionPredicate().test(new SourceFailure.Unavailable("HTTP 503", true))).isTrue();
            assertThat(retry.getExceptionPredicate().test(new SourceFailure.Unavailable("HTTP 404"))).isFalse();
        }
    }

    @Test
    void redisHasACircuitOnly() {
        assertThat(circuits.find("redis")).isPresent();
        assertThat(bulkheads.find("redis")).isEmpty();
        assertThat(retries.find("redis")).isEmpty();
    }

    @Test
    void circuitStateAndRefusedCallsAreMetered() {
        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "source-github").tag("state", "open").gauge()).isNotNull();
        assertThat(meters.find("resilience4j.circuitbreaker.not.permitted.calls").tag("name", "redis").counter()).isNotNull();
        assertThat(meters.find("resilience4j.bulkhead.available.concurrent.calls").tag("name", "source-tavily").gauge()).isNotNull();
    }
}
