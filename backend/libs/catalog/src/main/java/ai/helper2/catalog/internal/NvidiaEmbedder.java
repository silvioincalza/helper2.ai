package ai.helper2.catalog.internal;

import ai.helper2.catalog.Embedder;
import ai.helper2.platform.config.HttpClientProperties;
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

  public NvidiaEmbedder(
      String baseUrl,
      String apiKey,
      String model,
      int dimensions,
      Duration timeout,
      HttpClientProperties http,
      ObservationRegistry observations,
      Resilience resilience,
      String policy) {
    resilience.require(policy);
    var factory =
        new BufferingClientHttpRequestFactory(
            new InterceptingClientHttpRequestFactory(
                ObservedHttp.requestFactory(timeout, timeout, false, http),
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
    List<String> input = texts.stream().map(NvidiaEmbedder::withoutBase64DataUris).toList();
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
                              input,
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

  static String withoutBase64DataUris(String text) {
    return BASE64_DATA_URI.matcher(text).replaceAll(" ");
  }

  private static final Pattern BASE64_DATA_URI =
      Pattern.compile(
          "data:[^\\s,;]{0,128}(?:;[^\\s,;]{0,64}){0,4};base64,[A-Za-z0-9+/=_-]*+",
          Pattern.CASE_INSENSITIVE);

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

  static String shortened(String body) {
    String oneLine = CONTROL.matcher(body == null ? "" : body).replaceAll(" ").strip();
    return oneLine.length() <= MAX_REASON ? oneLine : oneLine.substring(0, MAX_REASON) + "…";
  }

  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]+");

  static final int MAX_REASON = 200;

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
