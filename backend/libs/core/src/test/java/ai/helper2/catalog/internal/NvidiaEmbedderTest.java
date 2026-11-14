package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.helper2.catalog.Embedder;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.resilience.Resilience4jPolicies;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
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
  @Test
  void aBatchIsSentWithAContentLengthNotChunked() {
    int n = 50;
    String vector =
        IntStream.range(0, 4).mapToObj(i -> "0.5").collect(Collectors.joining(",", "[", "]"));
    String data =
        IntStream.range(0, n)
            .mapToObj(i -> "{\"index\":" + i + ",\"embedding\":" + vector + "}")
            .collect(Collectors.joining(",", "{\"data\":[", "]}"));
    try (FixtureServer server =
        new FixtureServer().route("/v1/embeddings", FixtureServer.Reply.ok(data))) {
      NvidiaEmbedder embedder =
          new NvidiaEmbedder(
              server.baseUrl() + "/v1",
              "key",
              "m",
              4,
              Duration.ofSeconds(5),
              TestHttp.SETTINGS,
              ObservationRegistry.NOOP,
              Resilience.NONE,
              LlmPolicies.EMBED);
      List<String> texts =
          IntStream.range(0, n).mapToObj(i -> "text number " + i + " ".repeat(300)).toList();

      List<float[]> vectors = embedder.embed(texts, Embedder.Kind.PASSAGE);

      Map<String, List<String>> headers = server.requestHeaders.getFirst();
      assertThat(vectors).hasSize(n);
      assertThat(headers).containsKey("Content-length");
      assertThat(headers).doesNotContainKey("Transfer-encoding");
      assertThat(server.requestBodies.getFirst()).contains("\"input_type\":\"passage\"");
    }
  }

  @Test
  void eachCallIsTimed() {
    MeterRegistry meters = new SimpleMeterRegistry();
    ObservationRegistry observations = ObservationRegistry.create();
    observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
    try (FixtureServer server =
        new FixtureServer()
            .route(
                "/v1/embeddings",
                FixtureServer.Reply.ok("{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5]}]}"))) {
      new NvidiaEmbedder(
              server.baseUrl() + "/v1",
              "key",
              "m",
              2,
              Duration.ofSeconds(5),
              TestHttp.SETTINGS,
              observations,
              Resilience.NONE,
              LlmPolicies.EMBED)
          .embed(List.of("query"), Embedder.Kind.QUERY);

      var timer = meters.get("http.client.requests").timer();
      assertThat(timer.count()).isEqualTo(1);
      assertThat(timer.getId().getTag("status")).isEqualTo("200");
      assertThat(timer.getId().getTag("uri")).endsWith("/embeddings").doesNotContain("?");
    }
  }

  @Test
  void theIndexersOpenCircuitLeavesSearchQueriesAlone() {
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    circuits.circuitBreaker(LlmPolicies.EMBED);
    circuits.circuitBreaker(LlmPolicies.EMBED_INDEX).transitionToForcedOpenState();
    Resilience resilience =
        new Resilience4jPolicies(
            circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults());
    try (FixtureServer server =
        new FixtureServer()
            .route(
                "/v1/embeddings",
                FixtureServer.Reply.ok("{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5]}]}"))) {
      String url = server.baseUrl() + "/v1";
      Duration timeout = Duration.ofSeconds(5);
      var indexing =
          new NvidiaEmbedder(
              url,
              "key",
              "m",
              2,
              timeout,
              TestHttp.SETTINGS,
              ObservationRegistry.NOOP,
              resilience,
              LlmPolicies.EMBED_INDEX);
      var search =
          new NvidiaEmbedder(
              url,
              "key",
              "m",
              2,
              timeout,
              TestHttp.SETTINGS,
              ObservationRegistry.NOOP,
              resilience,
              LlmPolicies.EMBED);

      assertThatThrownBy(() -> indexing.embed(List.of("text"), Embedder.Kind.PASSAGE))
          .isInstanceOfSatisfying(
              Embedder.EmbeddingFailure.class,
              e -> assertThat(e.reason()).isEqualTo(Embedder.FailureReason.REFUSED));
      assertThat(search.embed(List.of("query"), Embedder.Kind.QUERY)).hasSize(1);
      assertThat(server.requestBodies).hasSize(1);
    }
  }

  @Test
  void anOpenCircuitFailsAtOnceWithoutCallingTheApi() {
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    circuits.circuitBreaker(LlmPolicies.EMBED).transitionToForcedOpenState();
    Resilience resilience =
        new Resilience4jPolicies(
            circuits, BulkheadRegistry.ofDefaults(), RetryRegistry.ofDefaults());
    try (FixtureServer server =
        new FixtureServer()
            .route(
                "/v1/embeddings",
                FixtureServer.Reply.ok("{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5]}]}"))) {
      NvidiaEmbedder embedder =
          new NvidiaEmbedder(
              server.baseUrl() + "/v1",
              "key",
              "m",
              2,
              Duration.ofSeconds(5),
              TestHttp.SETTINGS,
              ObservationRegistry.NOOP,
              resilience,
              LlmPolicies.EMBED);

      assertThatThrownBy(() -> embedder.embed(List.of("query"), Embedder.Kind.QUERY))
          .isInstanceOf(Embedder.EmbeddingFailure.class);
      assertThat(server.requestBodies).isEmpty();
    }
  }

  @Test
  void aResponseThatTricklesInStopsAtTheTimeout() {
    try (FixtureServer server =
        new FixtureServer()
            .route(
                "/v1/embeddings",
                FixtureServer.Reply.drip(
                    "{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5]}]}", 50))) {
      NvidiaEmbedder embedder =
          new NvidiaEmbedder(
              server.baseUrl() + "/v1",
              "key",
              "m",
              2,
              Duration.ofMillis(500),
              TestHttp.SETTINGS,
              ObservationRegistry.NOOP,
              Resilience.NONE,
              LlmPolicies.EMBED);
      long start = System.nanoTime();

      assertThatThrownBy(() -> embedder.embed(List.of("query"), Embedder.Kind.QUERY))
          .isInstanceOf(Embedder.EmbeddingFailure.class)
          .hasMessageContaining("deadline exceeded");
      assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_500);
    }
  }

  private static NvidiaEmbedder embedderAt(FixtureServer server) {
    return new NvidiaEmbedder(
        server.baseUrl() + "/v1",
        "key",
        "m",
        2,
        Duration.ofSeconds(5),
        TestHttp.SETTINGS,
        ObservationRegistry.NOOP,
        Resilience.NONE,
        LlmPolicies.EMBED_INDEX);
  }

  @Test
  void aProviderErrorOnCatalogTextsCarriesTheShortenedBody() {
    String body =
        "{\"object\":\"error\",\"message\":\"service inference failed\"}\r\nlog: forged"
            + "x".repeat(1_000);
    try (FixtureServer server =
        new FixtureServer()
            .route("/v1/embeddings", new FixtureServer.Reply(500, body, Map.of(), 0))) {
      assertThatThrownBy(() -> embedderAt(server).embed(List.of("a", "b"), Embedder.Kind.PASSAGE))
          .isInstanceOfSatisfying(
              Embedder.EmbeddingFailure.class,
              e -> {
                assertThat(e.reason()).isEqualTo(Embedder.FailureReason.PROVIDER_ERROR);
                assertThat(e.getMessage())
                    .startsWith("embedding call failed: HTTP 500")
                    .contains("service inference failed")
                    .doesNotContain("\r", "\n");
                assertThat(e.getMessage().length()).isLessThan(300);
              });
    }
  }

  @Test
  void aProviderErrorOnASearchQueryLeavesTheBodyOut() {
    try (FixtureServer server =
        new FixtureServer()
            .route(
                "/v1/embeddings",
                new FixtureServer.Reply(
                    500, "{\"message\":\"bad input: my query\"}", Map.of(), 0))) {
      assertThatThrownBy(() -> embedderAt(server).embed(List.of("my query"), Embedder.Kind.QUERY))
          .isInstanceOfSatisfying(
              Embedder.EmbeddingFailure.class,
              e -> assertThat(e.getMessage()).isEqualTo("embedding call failed: HTTP 500"));
    }
  }

  @Test
  void tooManyRequestsIsARateLimit() {
    try (FixtureServer server =
        new FixtureServer()
            .route("/v1/embeddings", new FixtureServer.Reply(429, "{}", Map.of(), 0))) {
      assertThatThrownBy(() -> embedderAt(server).embed(List.of("a"), Embedder.Kind.PASSAGE))
          .isInstanceOfSatisfying(
              Embedder.EmbeddingFailure.class, e -> assertThat(e.rateLimited()).isTrue());
    }
  }

  @Test
  void anUnprocessableInputIsAProviderError() {
    try (FixtureServer server =
        new FixtureServer()
            .route(
                "/v1/embeddings",
                new FixtureServer.Reply(
                    422, "{\"message\":\"failed to parse VLM input\"}", Map.of(), 0))) {
      assertThatThrownBy(() -> embedderAt(server).embed(List.of("a"), Embedder.Kind.PASSAGE))
          .isInstanceOfSatisfying(
              Embedder.EmbeddingFailure.class,
              e -> {
                assertThat(e.reason()).isEqualTo(Embedder.FailureReason.PROVIDER_ERROR);
                assertThat(e.getMessage())
                    .startsWith("embedding call failed: HTTP 422")
                    .contains("failed to parse VLM input");
              });
    }
  }

  @Test
  void noBase64DataUriIsSentToTheApi() {
    try (FixtureServer server =
        new FixtureServer()
            .route(
                "/v1/embeddings",
                FixtureServer.Reply.ok("{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}"))) {
      embedderAt(server)
          .embed(
              List.of("Embedding models\n{\"url\": \"data:image/png;base64,BASE64_IMAGE_DATA\"}"),
              Embedder.Kind.PASSAGE);

      assertThat(server.requestBodies.getFirst())
          .contains("Embedding models")
          .doesNotContain("base64", "BASE64_IMAGE_DATA");
    }
  }

  @Test
  void base64DataUrisAreRemovedWhereverTheyStand() {
    assertThat(NvidiaEmbedder.withoutBase64DataUris("data:image/png;base64,iVBORw0KGgo= logo"))
        .isEqualTo("  logo");
    assertThat(NvidiaEmbedder.withoutBase64DataUris("see (data:image/png;base64,AAAA_-x) here"))
        .isEqualTo("see ( ) here");
    assertThat(NvidiaEmbedder.withoutBase64DataUris("a DATA:IMAGE/JPEG;BASE64,QUJD b"))
        .isEqualTo("a   b");
    assertThat(
            NvidiaEmbedder.withoutBase64DataUris(
                "x data:audio/wav;base64,UklG y data:;base64,QQ== z"))
        .isEqualTo("x   y   z");
    assertThat(
            NvidiaEmbedder.withoutBase64DataUris(
                "a data:image/svg+xml;charset=utf-8;base64,PHN2Zz4= b"))
        .isEqualTo("a   b");
  }

  @Test
  void otherTextIsLeftAsItIs() {
    for (String text :
        List.of(
            "[![logo](data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%3E%3C/svg%3E)]",
            "import \"encoding/base64\" // accepting a Base64 image data URI",
            "encode your audio as base64 PCM16",
            "plain text")) {
      assertThat(NvidiaEmbedder.withoutBase64DataUris(text)).isEqualTo(text);
    }
  }

  @Test
  void aLongNearMatchIsReadQuickly() {
    String text = "data:".repeat(5_000) + ";x".repeat(20_000);

    assertTimeoutPreemptively(
        Duration.ofSeconds(2), () -> NvidiaEmbedder.withoutBase64DataUris(text));
  }
}
