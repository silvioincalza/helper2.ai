package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience4jPolicies;
import ai.helper2.search.CachedHits;
import ai.helper2.search.SearchCache;
import ai.helper2.search.SearchRequest;
import ai.helper2.search.SkipReason;
import ai.helper2.search.SourceOutcome;
import ai.helper2.search.SourceStatus;
import ai.helper2.search.usecase.SearchResources;
import ai.helper2.sources.npm.NpmSource;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.MovableClock;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A real source adapter behind its circuit, over HTTP: repeated 503s open it, then a search skips the source without a call. */
class SourceCircuitIT {

    private final FixtureServer server = new FixtureServer();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-30T10:00:00Z"));

    @AfterEach
    void stop() {
        server.close();
        executor.shutdownNow();
    }

    private static final SearchCache NO_CACHE = new SearchCache() {
        @Override
        public CachedHits get(String key) {
            return null;
        }

        @Override
        public void put(String key, CachedHits value, Duration ttl) {
        }
    };

    private SearchResources search() {
        CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
        circuits.circuitBreaker("source-npm", CircuitBreakerConfig.custom()
            .slidingWindowSize(4).minimumNumberOfCalls(3).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(1).clock(clock).build());
        NpmSource npm = new NpmSource(new SourceSettings(true, server.baseUrl(), "", Duration.ofSeconds(2), Duration.ofMinutes(30)),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP), clock);
        return new SearchResources(List.of(npm), NO_CACHE, results -> Map.of(), executor, clock, Duration.ofHours(24), 30,
            ai.helper2.search.SearchMetrics.NOOP, new ai.helper2.search.QueryAnalyzer(), new ai.helper2.search.ResultMerger(),
            new ai.helper2.search.Ranking(), null, ai.helper2.search.QueryTranslator.NONE, Duration.ofMillis(100),
            new Resilience4jPolicies(circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults()));
    }

    private SourceStatus npm(SearchResources search, String q) {
        return search.search(new SearchRequest(q)).getStatuses().getFirst();
    }

    @Test
    void threeServerErrorsOpenTheCircuitThenTheSourceIsSkippedWithoutACallUntilATrialCallSucceeds() {
        server.route("/-/v1/search", new FixtureServer.Reply(503, "{}", Map.of(), 0));
        SearchResources search = search();

        for (int i = 0; i < 3; i++) {
            assertThat(npm(search, "agent " + i).getOutcome()).isEqualTo(SourceOutcome.FAILED);
        }
        SourceStatus open = npm(search, "agent 4");
        assertThat(open.getOutcome()).isEqualTo(SourceOutcome.SKIPPED);
        assertThat(open.getSkipReason()).isEqualTo(SkipReason.CIRCUIT_OPEN);
        assertThat(server.requests).hasSize(3);

        server.route("/-/v1/search", FixtureServer.Reply.ok("{\"objects\":[]}"));
        clock.now = clock.now.plusSeconds(31);
        assertThat(npm(search, "agent 5").getOutcome()).as("half-open trial call").isEqualTo(SourceOutcome.SUCCEEDED);
        assertThat(npm(search, "agent 6").getOutcome()).isEqualTo(SourceOutcome.SUCCEEDED);
        assertThat(server.requests).hasSize(5);
    }
}
