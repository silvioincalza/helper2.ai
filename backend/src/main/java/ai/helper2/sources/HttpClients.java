package ai.helper2.sources;

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

/**
 * Source call plumbing: base URL and token, uniform failure mapping and hit parsing. The transport itself
 * (timeouts, User-Agent, observation) is {@link ai.helper2.platform.http.ObservedHttp}.
 */
final class HttpClients {

    private static final Logger log = LoggerFactory.getLogger(HttpClients.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private HttpClients() {
    }

    static RestClient client(RestClient.Builder builder, SourceSettings settings) {
        return authorized(builder.baseUrl(settings.baseUrl()), settings).build();
    }

    static RestClient gitHubClient(RestClient.Builder builder, SourceSettings settings) {
        return authorized(builder.baseUrl(settings.baseUrl())
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28"), settings).build();
    }

    private static RestClient.Builder authorized(RestClient.Builder b, SourceSettings settings) {
        return settings.hasToken() ? b.defaultHeader("Authorization", "Bearer " + settings.token()) : b;
    }

    /**
     * The link to show for a source URL: the URL as the source wrote it, trimmed, when it is a valid http(s)
     * URI; otherwise the canonical form, which is always valid (e.g. "git+https://..." repository links).
     */
    static URI link(String raw, CanonicalUrl canonical) {
        try {
            URI uri = new URI(raw.strip());
            String scheme = uri.getScheme();
            if (uri.getHost() != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                return uri;
            }
        } catch (URISyntaxException e) {
            // fall through to the canonical form
        }
        return URI.create(canonical.getValue());
    }

    static String queryText(SourceQuery query) {
        return String.join(" ", query.getText(), String.join(" ", query.getExpandedTerms())).trim();
    }

    /**
     * Hugging Face Hub listing parameters (models and Spaces). With a window, trending first: the Hub sorts by
     * creation date too, but its newest entries are mostly test checkpoints; trending ones are new and used. The
     * search then keeps only those created inside the window. Browsing sends no search words: the Hub is AI-only.
     */
    static UriBuilder hubListing(UriBuilder u, SourceQuery query, String text) {
        if (!query.getBrowse()) {
            u.queryParam("search", text);
        }
        if (query.getPublishedAfter() != null) {
            u.queryParam("sort", "trendingScore");
        }
        return u;
    }

    /** Runs a call that returns plain text (e.g. a raw README) with the same failure mapping as {@link #getJson}. */
    static String getText(Supplier<String> call, Clock clock) {
        try {
            String body = call.get();
            return body == null ? "" : body;
        } catch (RestClientResponseException e) {
            throw mapStatus(e, clock);
        } catch (RestClientException e) {
            if (ObservedHttp.isTooLarge(e)) {
                throw new SourceFailure.Unavailable("response too large");
            }
            throw new SourceFailure.Unavailable(e instanceof ResourceAccessException ? "network error or timeout" : "http error");
        }
    }

    /** Runs a call and maps transport, HTTP and parsing errors to {@link SourceFailure}s. */
    static JsonNode getJson(Supplier<String> call, Clock clock) {
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
        // 503 with Retry-After: the service asks for a pause (arXiv does this when called too often).
        boolean pause = status == 503 && h.getFirst(HttpHeaders.RETRY_AFTER) != null;
        if (status == 429 || (status == 403 && exhausted) || pause) {
            return new SourceFailure.RateLimited(retryAfter(h, clock));
        }
        return new SourceFailure.Unavailable("HTTP " + status);
    }

    private static Duration retryAfter(HttpHeaders h, Clock clock) {
        try {
            String ra = h.getFirst(HttpHeaders.RETRY_AFTER);
            if (ra != null) {
                return Duration.ofSeconds(Long.parseLong(ra.trim()));
            }
            String reset = h.getFirst("x-ratelimit-reset");
            if (reset != null) {
                Duration d = Duration.between(clock.instant(), Instant.ofEpochSecond(Long.parseLong(reset.trim())));
                return d.isNegative() ? Duration.ofSeconds(1) : d;
            }
        } catch (NumberFormatException ignored) {
            // fall through to default
        }
        return null;
    }

    @FunctionalInterface
    interface HitMapper {
        SourceHit toHit(JsonNode item, Instant now, int rank);
    }

    /**
     * Maps each item; null means "not for us". An item whose mapping throws is malformed source data: it is
     * dropped and counted in {@value #ITEMS_DROPPED} by source and exception type, and the other items are kept.
     */
    static List<SourceHit> hits(String sourceId, Iterable<JsonNode> items, Instant now, HitMapper mapper) {
        List<SourceHit> hits = new ArrayList<>();
        int rank = 0;
        for (JsonNode item : items) {
            try {
                SourceHit hit = mapper.toHit(item, now, rank++);
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

    /** Counts one malformed source item; the reason is the exception type, never source text. */
    static void dropped(String sourceId, RuntimeException e) {
        log.debug("{}: malformed item dropped: {}", sourceId, e.getClass().getSimpleName());
        Metrics.counter(ITEMS_DROPPED, "source", sourceId, "reason", e.getClass().getSimpleName()).increment();
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
    }

    static Instant instant(JsonNode n, String field) {
        String v = text(n, field);
        try {
            return v == null ? null : Instant.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
