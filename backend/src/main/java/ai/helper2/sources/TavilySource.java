package ai.helper2.sources;

import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Tavily web search (topic "news") for AI news, articles and events. Docs:
 * https://docs.tavily.com/documentation/api-reference/endpoint/search — needs H2_TAVILY_API_KEY; without a
 * key the source is disabled and reported as SKIPPED. One credit per call: the source is metered (ADR 0037). published_date is Tavily's own estimate and stays
 * unknown when Tavily has none. Event dates are never inferred.
 */
public class TavilySource implements SearchSource {

    private static final Pattern EVENT = Pattern.compile(
        "\\b(conference|summit|meetup|hackathon|webinar|workshop|expo|symposium|keynote|devday|event)\\b", Pattern.CASE_INSENSITIVE);

    private final RestClient http;
    private final Clock clock;
    private final SourceDescriptor descriptor;

    public TavilySource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
        this.clock = clock;
        this.http = HttpClients.client(builder, settings);
        this.descriptor = new SourceDescriptor("tavily", "Tavily web search (AI news)", SourceMode.LIVE,
            URI.create("https://tavily.com"), URI.create("https://tavily.com/terms"),
            "Web results via the Tavily Search API; each links to the original publisher.",
            EnumSet.of(ResourceType.NEWS, ResourceType.ARTICLE, ResourceType.EVENT),
            // Metered: each call costs a credit, so searches read stored result sets first, with their own freshness,
            // and call live only inside the daily quota (ADR 0037); the per-source cache TTL does not apply.
            settings.timeout(), Duration.ZERO, settings.enabled() && settings.hasToken(), 48, true, true, true, true, true);
    }

    @Override
    public SourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public List<SourceHit> search(SourceQuery query) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
            "query", queryText(query),
            "topic", topic(query),
            "search_depth", "basic",
            "max_results", Math.min(query.getLimit(), 20)));
        if (query.getPublishedAfter() != null) {
            // Tavily's documented window: results published on or after this day (YYYY-MM-DD).
            body.put("start_date", query.getPublishedAfter().toString());
        }
        JsonNode root = HttpClients.getJson(() -> http.post().uri("/search").body(body).retrieve().body(String.class), clock);
        return HttpClients.hits("tavily", root.path("results"), clock.instant(), (item, now, rank) -> toHit(item, "tavily", now, rank));
    }

    /**
     * News, except for a guide question without a period ("what is RAG?"): there the general web finds explainers and
     * product pages, which news search does not. Non-AI pages are still dropped by the gate in {@link #toHit}.
     */
    static String topic(SourceQuery query) {
        return query.getWebQuery() != null && query.getPublishedAfter() == null ? "general" : "news";
    }

    /**
     * The guide's planned web query when there is one: it is already a full English phrase with the AI
     * context made explicit. Otherwise the catalog keywords plus expansions, biased towards the AI world;
     * either way non-AI results are dropped by the relevance gate in {@link #toHit}.
     */
    static String queryText(SourceQuery query) {
        if (query.getWebQuery() != null) {
            return query.getWebQuery();
        }
        String text = HttpClients.queryText(query);
        return text + " artificial intelligence";
    }

    /** Maps one Tavily result; shared with the Scout's Tavily collector. null when unusable or not about AI. */
    static SourceHit toHit(JsonNode r, String sourceId, Instant now, int position) {
        String url = HttpClients.text(r, "url");
        String title = HttpClients.text(r, "title");
        CanonicalUrl canonical = CanonicalUrl.of(url);
        if (url == null || title == null || canonical == null) {
            return null;
        }
        String content = HttpClients.text(r, "content");
        if (!Classification.isAiRelated(title, content, Set.of())) {
            return null;
        }
        ResourceType type = EVENT.matcher(title).find() ? ResourceType.EVENT : ResourceType.NEWS;
        String snippet = content == null ? null : content.length() > 400 ? content.substring(0, 397) + "…" : content;
        return new SourceHit(sourceId, canonical.getValue(), HttpClients.link(url, canonical), canonical, null, title, snippet,
            ValueOrigin.SOURCE_PROVIDED, type, Audience.BOTH, null, Set.of(), Set.of(), null, null, Set.of(), null,
            DateFact.fromSource(date(HttpClients.text(r, "published_date")), sourceId, now),
            DateFact.unknown(), List.of(), now, position);
    }

    /** ISO-8601 or RFC 1123 ("Mon, 20 Oct 2026 14:00:00 GMT"); anything else is unknown. */
    static Instant date(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException ignored) {
            // try RFC 1123
        }
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH)).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
