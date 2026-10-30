package ai.helper2.sources.tavily;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Tavily news search with a fixed list of discovery queries ("new AI tool launched", ...), run on a
 * long interval because every query costs one API credit (search_depth basic). The results are news
 * and articles ABOUT new tools, catalogued as NEWS/ARTICLE/EVENT with Tavily's snippet as the
 * description, so the LLM enrichment can cite them. A pass asks only for results published since
 * the previous complete pass (start_date), or the last week on the first run.
 * Docs: https://docs.tavily.com/documentation/api-reference/endpoint/search
 */
public class TavilyCollector implements Collector {

    static final String ID = "tavily-scout";
    /** Circuit breaker and retry of this collector\'s calls (ADR 0033). */
    static final String POLICY = "collector-" + ID;
    private static final int MAX_RESULTS_PER_QUERY = 20;

    private final RestClient http;
    private final Clock clock;
    private final Resilience resilience;
    private final RunInterval interval;
    private final List<String> queries;
    private final SourceDescriptor descriptor;

    public TavilyCollector(SourceSettings settings, RestClient.Builder builder, Clock clock, Resilience resilience,
                           RunInterval interval, List<String> queries) {
        this.clock = clock;
        this.resilience = resilience;
        if (settings.enabled()) {
            resilience.require(POLICY);
        }
        this.interval = interval;
        this.queries = List.copyOf(queries);
        this.http = HttpClients.client(builder, settings);
        this.descriptor = new SourceDescriptor(ID, "Tavily discovery queries (AI news)", SourceMode.INDEXED,
            URI.create("https://tavily.com"), URI.create("https://tavily.com/terms"),
            "Web results via the Tavily Search API; each links to the original publisher.",
            EnumSet.of(ResourceType.NEWS, ResourceType.ARTICLE, ResourceType.EVENT),
            settings.timeout(), settings.cacheTtl(), settings.enabled() && settings.hasToken() && !this.queries.isEmpty(), 48);
    }

    @Override
    public SourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public CollectedBatch collect(String checkpoint, String resumeCursor, int maxItems) {
        Instant startedAt = clock.instant();
        if (!interval.allowsPass(checkpoint, resumeCursor, startedAt)) {
            return CollectedBatch.notDue();
        }
        if (queries.isEmpty()) {
            return new CollectedBatch(List.of(), null, startedAt.toString());
        }
        Map<String, SourceHit> byUrl = new LinkedHashMap<>();
        int perQuery = Math.max(1, Math.min(MAX_RESULTS_PER_QUERY, maxItems / queries.size()));
        for (String query : queries) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("topic", "news");
            body.put("search_depth", "basic");
            body.put("max_results", perQuery);
            String since = startDate(checkpoint);
            if (since != null) {
                body.put("start_date", since);
            } else {
                body.put("time_range", "week");
            }
            JsonNode root = resilience.call(POLICY, () -> HttpClients.getJson(() -> http.post().uri("/search").body(body).retrieve().body(String.class), clock));
            for (JsonNode r : root.path("results")) {
                SourceHit hit = TavilySource.toHit(r, ID, startedAt, byUrl.size());
                if (hit != null) {
                    byUrl.putIfAbsent(hit.getExternalId(), hit);
                }
            }
        }
        return new CollectedBatch(List.copyOf(byUrl.values()), null, startedAt.toString());
    }

    /** UTC day of the previous complete pass; null on the first run or for an unreadable checkpoint. */
    static String startDate(String checkpoint) {
        if (checkpoint == null) {
            return null;
        }
        try {
            return Instant.parse(checkpoint).atZone(ZoneOffset.UTC).toLocalDate().toString();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
