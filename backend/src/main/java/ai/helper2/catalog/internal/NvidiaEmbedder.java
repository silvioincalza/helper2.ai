package ai.helper2.catalog.internal;

import ai.helper2.catalog.Embedder;
import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * NVIDIA API catalog embeddings (OpenAI-compatible {@code POST /v1/embeddings} plus NVIDIA's {@code
 * input_type} and {@code truncate}). Docs:
 * https://docs.nvidia.com/nim/nemo-retriever/embedding/latest/use-the-api-openai.html Nemotron 3.5
 * Embed accepts any dimension from 128 to 2048 (Matryoshka: first N values, re-normalised); the
 * schema stores 1024, under the 2000 limit of a pgvector HNSW index. Every call runs under the
 * circuit it is given (ADR 0033: llm-embed for queries, llm-embed-index for the indexer): while the
 * API is down, embeddings fail at once instead of after their timeout.
 */
public class NvidiaEmbedder implements Embedder {

  record Request(
      String model,
      List<String> input,
      String input_type,
      Integer dimensions,
      String truncate,
      String encoding_format) {}

  record Response(List<Item> data) {
    record Item(int index, float[] embedding) {}
  }

  private final RestClient http;
  private final String model;
  private final int dimensions;
  private final Resilience resilience;
  private final String policy;

  /**
   * Observed like the source calls (ObservedHttp): {@code timeout} to connect, to wait for response
   * data, to wait for a pooled connection and as the call's total deadline; redirects are not
   * followed, so the API key never goes to another host.
   */
  public NvidiaEmbedder(
      String baseUrl,
      String apiKey,
      String model,
      int dimensions,
      Duration timeout,
      ObservationRegistry observations,
      Resilience resilience,
      String policy) {
    resilience.require(policy);
    // Buffered, so the request carries a Content-Length: a streamed (chunked) body of 50 texts made
    // the API catalog hang until the read timeout, while one short query went through (27 Oct
    // 2026).
    // Its timeout is also the call's total deadline (ADR 0006 §2, 11). The deadline sits under the
    // buffering, so the buffered read of the response is the read it stops.
    var factory =
        new BufferingClientHttpRequestFactory(
            new InterceptingClientHttpRequestFactory(
                ObservedHttp.requestFactory(timeout, timeout, false),
                List.of(ObservedHttp.deadlineOnly(timeout))));
    this.http =
        ObservedHttp.observed(factory, observations)
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    this.model = model;
    this.dimensions = dimensions;
    this.resilience = resilience;
    this.policy = policy;
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  @Override
  public List<float[]> embed(List<String> texts, Kind kind) {
    if (texts.isEmpty()) {
      return List.of();
    }
    Response response;
    try {
      response =
          resilience.call(
              policy,
              () ->
                  http.post()
                      .uri("/embeddings")
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(
                          new Request(
                              model,
                              texts,
                              kind == Kind.QUERY ? "query" : "passage",
                              dimensions,
                              "END",
                              "float"))
                      .retrieve()
                      .body(Response.class));
    } catch (CallRefused e) {
      throw new EmbeddingFailure("embedding call refused: " + e.reason(), FailureReason.REFUSED);
    } catch (RestClientResponseException e) {
      throw providerFailure(e, kind);
    } catch (RestClientException e) {
      String why =
          ObservedHttp.isDeadlineExceeded(e) ? "deadline exceeded" : e.getClass().getSimpleName();
      throw new EmbeddingFailure("embedding call failed: " + why, FailureReason.UNAVAILABLE);
    }
    return vectors(response, texts.size(), dimensions);
  }

  /**
   * The status, and for catalog texts the provider's reason from the body (NVIDIA's 500 says
   * "service inference failed"; 5 Nov 2026). A body can echo the input, and a search query is the
   * visitor's text, so a query's failure carries the status only.
   */
  private static EmbeddingFailure providerFailure(RestClientResponseException e, Kind kind) {
    String message = "embedding call failed: HTTP " + e.getStatusCode().value();
    if (kind == Kind.PASSAGE) {
      String reason = shortened(e.getResponseBodyAsString());
      if (!reason.isEmpty()) {
        message += ": " + reason;
      }
    }
    FailureReason reason =
        e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
            ? FailureReason.RATE_LIMITED
            : FailureReason.PROVIDER_ERROR;
    return new EmbeddingFailure(message, reason);
  }

  /** Untrusted text for one log line: control characters become spaces, at most 200 chars. */
  static String shortened(String body) {
    String oneLine = CONTROL.matcher(body == null ? "" : body).replaceAll(" ").strip();
    return oneLine.length() <= MAX_REASON ? oneLine : oneLine.substring(0, MAX_REASON) + "…";
  }

  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]+");
  private static final int MAX_REASON = 200;

  /** Untrusted shape: every input needs exactly one vector of the expected size. */
  static List<float[]> vectors(Response response, int count, int dimensions) {
    if (response == null || response.data() == null || response.data().size() != count) {
      throw new EmbeddingFailure(
          "embedding response has the wrong number of vectors", FailureReason.INVALID_RESPONSE);
    }
    Map<Integer, float[]> byIndex = new LinkedHashMap<>();
    response.data().stream()
        .sorted(Comparator.comparingInt(Response.Item::index))
        .forEach(i -> byIndex.put(i.index(), i.embedding()));
    List<float[]> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      float[] v = byIndex.get(i);
      if (v == null || v.length != dimensions) {
        throw new EmbeddingFailure(
            "embedding response has a missing or wrong-sized vector",
            FailureReason.INVALID_RESPONSE);
      }
      out.add(v);
    }
    return out;
  }
}
