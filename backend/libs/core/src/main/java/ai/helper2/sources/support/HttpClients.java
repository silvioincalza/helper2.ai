package ai.helper2.sources.support;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.CanonicalUrl;
import io.micrometer.core.instrument.Metrics;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class HttpClients {

  private static final Logger log = LoggerFactory.getLogger(HttpClients.class);
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private HttpClients() {}

  public static RestClient client(RestClient.Builder builder, SourceSettings settings) {
    return authorized(builder.baseUrl(settings.baseUrl()), settings).build();
  }

  public static RestClient gitHubClient(RestClient.Builder builder, SourceSettings settings) {
    return authorized(
            builder
                .baseUrl(settings.baseUrl())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28"),
            settings)
        .build();
  }

  private static RestClient.Builder authorized(RestClient.Builder b, SourceSettings settings) {
    return settings.hasToken() ? b.defaultHeader("Authorization", "Bearer " + settings.token()) : b;
  }

  public static URI link(String raw, CanonicalUrl canonical) {
    try {
      URI uri = new URI(raw.strip());
      String scheme = uri.getScheme();
      if (uri.getHost() != null
          && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
        return uri;
      }
    } catch (URISyntaxException e) {
    }
    return URI.create(canonical.getValue());
  }

  public static String plainWords(String text) {
    return text.replaceAll("[^\\p{L}\\p{N} -]", " ").trim().replaceAll("\\s+", " ");
  }

  public static int fetchSize(SourceQuery query) {
    return Math.min(query.getLimit() * 3, 50);
  }

  public static List<SourceHit> firstHits(List<SourceHit> hits, SourceQuery query) {
    return hits.size() <= query.getLimit() ? hits : List.copyOf(hits.subList(0, query.getLimit()));
  }

  public static String queryText(SourceQuery query) {
    return String.join(" ", query.getText(), String.join(" ", query.getExpandedTerms())).trim();
  }

  public static UriBuilder hubListing(UriBuilder u, SourceQuery query, String text) {
    if (!query.getBrowse() && !text.isBlank()) {
      u.queryParam("search", text);
    }
    if (query.getPublishedAfter() != null) {
      u.queryParam("sort", "trendingScore");
    }
    return u;
  }

  public static String getText(Supplier<String> call, Clock clock) {
    String body = get(call, clock);
    return body == null ? "" : body;
  }

  public static <T> T get(Supplier<T> call, Clock clock) {
    try {
      return call.get();
    } catch (RestClientResponseException e) {
      throw mapStatus(e, clock);
    } catch (RestClientException e) {
      throw networkFailure(e);
    }
  }

  private static SourceFailure networkFailure(RestClientException e) {
    if (ObservedHttp.isTooLarge(e)) {
      return new SourceFailure.Unavailable("response too large");
    }
    if (ObservedHttp.isDeadlineExceeded(e)) {
      return new SourceFailure.Unavailable("deadline exceeded", true);
    }
    boolean network = e instanceof ResourceAccessException;
    return new SourceFailure.Unavailable(
        network ? "network error or timeout" : "http error", network);
  }

  public static <T> T fetch(Supplier<T> call, Clock clock) {
    try {
      return call.get();
    } catch (RestClientResponseException e) {
      throw mapStatus(e, clock);
    } catch (RestClientException e) {
      throw networkFailure(e);
    }
  }

  public static JsonNode getJson(Supplier<String> call, Clock clock) {
    String body = getText(call, clock);
    try {
      JsonNode node = JSON.readTree(body == null ? "" : body);
      if (node == null || node.isMissingNode() || !(node.isObject() || node.isArray())) {
        throw new SourceFailure.Unavailable("unexpected response");
      }
      return node;
    } catch (JacksonException e) {
      throw new SourceFailure.Unavailable("malformed response");
    }
  }

  private static SourceFailure mapStatus(RestClientResponseException e, Clock clock) {
    HttpHeaders h = e.getResponseHeaders() == null ? new HttpHeaders() : e.getResponseHeaders();
    int status = e.getStatusCode().value();
    boolean exhausted = "0".equals(h.getFirst("x-ratelimit-remaining"));
    boolean pause = status == 503 && h.getFirst(HttpHeaders.RETRY_AFTER) != null;
    if (status == 429 || (status == 403 && exhausted) || pause) {
      return new SourceFailure.RateLimited(retryAfter(h, clock));
    }
    return new SourceFailure.Unavailable("HTTP " + status, status == 503);
  }

  private static Duration retryAfter(HttpHeaders h, Clock clock) {
    try {
      String ra = h.getFirst(HttpHeaders.RETRY_AFTER);
      if (ra != null) {
        return Duration.ofSeconds(Long.parseLong(ra.trim()));
      }
      String reset = h.getFirst("x-ratelimit-reset");
      if (reset != null) {
        Duration d =
            Duration.between(clock.instant(), Instant.ofEpochSecond(Long.parseLong(reset.trim())));
        return d.isNegative() ? Duration.ofSeconds(1) : d;
      }
    } catch (NumberFormatException ignored) {
    }
    return null;
  }

  @FunctionalInterface
  public interface HitMapper {
    SourceHit toHit(JsonNode item, Instant now, int rank);
  }

  public static List<SourceHit> hits(
      String sourceId, Iterable<JsonNode> items, Instant now, HitMapper mapper) {
    List<SourceHit> hits = new ArrayList<>();
    for (JsonNode item : items) {
      try {
        SourceHit hit = mapper.toHit(item, now, hits.size());
        if (hit != null) {
          hits.add(hit);
        }
      } catch (RuntimeException e) {
        dropped(sourceId, e);
      }
    }
    return hits;
  }

  static final String ITEMS_DROPPED = "helper2.source.items.dropped";

  public static void dropped(String sourceId, RuntimeException e) {
    dropped(sourceId, e.getClass().getSimpleName());
  }

  public static void dropped(String sourceId, String reason) {
    log.debug("{}: item dropped: {}", sourceId, reason);
    Metrics.counter(ITEMS_DROPPED, "source", sourceId, "reason", reason).increment();
  }

  public static String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (v.isMissingNode() || v.isNull()) {
      return null;
    }
    String clean = CONTROL.matcher(v.asString()).replaceAll("");
    return clean.isBlank() ? null : clean;
  }

  private static final java.util.regex.Pattern CONTROL =
      java.util.regex.Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

  public static Instant instant(JsonNode n, String field) {
    String v = text(n, field);
    try {
      return v == null ? null : Instant.parse(v);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
