package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.catalog.Embedder;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.resilience.Resilience4jPolicies;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import ai.helper2.testing.FixtureServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class NvidiaEmbedderTest {

    /**
     * The API catalog hung until the read timeout on a chunked request body of 50 texts (27 Oct 2026),
     * which stalled the prod index; a body with a Content-Length went through in about 1 s.
     */
    @Test
    void aBatchIsSentWithAContentLengthNotChunked() {
        int n = 50;
        String vector = IntStream.range(0, 4).mapToObj(i -> "0.5").collect(Collectors.joining(",", "[", "]"));
        String data = IntStream.range(0, n).mapToObj(i -> "{\"index\":" + i + ",\"embedding\":" + vector + "}")
            .collect(Collectors.joining(",", "{\"data\":[", "]}"));
        try (FixtureServer server = new FixtureServer().route("/v1/embeddings", FixtureServer.Reply.ok(data))) {
            NvidiaEmbedder embedder = new NvidiaEmbedder(server.baseUrl() + "/v1", "key", "m", 4, Duration.ofSeconds(5), ObservationRegistry.NOOP, Resilience.NONE);
            List<String> texts = IntStream.range(0, n).mapToObj(i -> "text number " + i + " ".repeat(300)).toList();

            List<float[]> vectors = embedder.embed(texts, Embedder.Kind.PASSAGE);

            Map<String, List<String>> headers = server.requestHeaders.getFirst();
            assertThat(vectors).hasSize(n);
            assertThat(headers).containsKey("Content-length");
            assertThat(headers).doesNotContainKey("Transfer-encoding");
            assertThat(server.requestBodies.getFirst()).contains("\"input_type\":\"passage\"");
        }
    }

    /** Observed like the source calls: one http.client.requests timer per call, tagged with the path only. */
    @Test
    void eachCallIsTimed() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        try (FixtureServer server = new FixtureServer()
                .route("/v1/embeddings", FixtureServer.Reply.ok("{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5]}]}"))) {
            new NvidiaEmbedder(server.baseUrl() + "/v1", "key", "m", 2, Duration.ofSeconds(5), observations, Resilience.NONE)
                .embed(List.of("query"), Embedder.Kind.QUERY);

            var timer = meters.get("http.client.requests").timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.getId().getTag("status")).isEqualTo("200");
            assertThat(timer.getId().getTag("uri")).endsWith("/embeddings").doesNotContain("?");
        }
    }

    /** While the llm-embed circuit is open, an embedding fails at once without reaching the API. */
    @Test
    void anOpenCircuitFailsAtOnceWithoutCallingTheApi() {
        CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
        circuits.circuitBreaker(LlmPolicies.EMBED).transitionToForcedOpenState();
        Resilience resilience = new Resilience4jPolicies(circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults());
        try (FixtureServer server = new FixtureServer()
                .route("/v1/embeddings", FixtureServer.Reply.ok("{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5]}]}"))) {
            NvidiaEmbedder embedder = new NvidiaEmbedder(server.baseUrl() + "/v1", "key", "m", 2, Duration.ofSeconds(5),
                ObservationRegistry.NOOP, resilience);

            assertThatThrownBy(() -> embedder.embed(List.of("query"), Embedder.Kind.QUERY)).isInstanceOf(Embedder.EmbeddingFailure.class);
            assertThat(server.requestBodies).isEmpty();
        }
    }
}
