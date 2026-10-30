package ai.helper2.sources;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateReview;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 * New AI courses and certifications (ADR 0032 §8, Source 3): one Tavily search per learning platform, restricted to
 * its domain with include_domains, on a weekly interval (one API credit per platform and pass, search_depth basic).
 * A result is kept only when it is on the platform's host and path, about AI, and reads like a course or
 * certification page. Kept pages go to the admin review list through {@link CandidateReview}; the batch handed back
 * to the Scout is always empty, so nothing this collector finds reaches the catalog without a person accepting it.
 * A pass asks only for pages published since the previous complete pass (start_date), or the last month on the first.
 * Docs: https://docs.tavily.com/documentation/api-reference/endpoint/search
 */
public class CourseCollector implements Collector {

    static final String ID = "course-scout";
    /** Circuit breaker and retry of this collector\'s calls (ADR 0033). */
    static final String POLICY = "collector-" + ID;
    private static final int MAX_RESULTS_PER_PLATFORM = 10;
    private static final Pattern COURSE_WORDS = Pattern.compile(
        "\\b(courses?|certifications?|certificates?|certified|specializations?|training|bootcamp|exam|class|learning path|"
            + "nanodegree|workshop|live event|tutorials?|lessons?)\\b", Pattern.CASE_INSENSITIVE);

    /** A platform to search: results must be on [host] (or a subdomain) under [pathPrefix]. */
    record Platform(String host, String pathPrefix, String name) {

        /** "host[/path]=Name", e.g. "huggingface.co/learn=Hugging Face Learn"; null when unreadable. */
        static Platform parse(String spec) {
            int eq = spec.indexOf('=');
            String where = (eq < 0 ? spec : spec.substring(0, eq)).strip().toLowerCase(Locale.ROOT);
            String name = eq < 0 ? null : spec.substring(eq + 1).strip();
            if (where.isEmpty() || where.contains("://")) {
                return null;
            }
            int slash = where.indexOf('/');
            String host = slash < 0 ? where : where.substring(0, slash);
            String path = slash < 0 ? "" : where.substring(slash).replaceAll("/+$", "");
            return host.contains(".") ? new Platform(host.replaceFirst("^www\\.", ""), path, name == null || name.isEmpty() ? host : name)
                : null;
        }

        boolean covers(CanonicalUrl url) {
            URI u = URI.create(url.getValue());
            String h = u.getHost();
            boolean onHost = h.equals(host) || h.endsWith("." + host);
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            return onHost && (pathPrefix.isEmpty() || path.equals(pathPrefix) || path.startsWith(pathPrefix + "/"));
        }
    }

    private final RestClient http;
    private final Clock clock;
    private final Resilience resilience;
    private final RunInterval interval;
    private final String query;
    private final List<Platform> platforms;
    private final CandidateReview review;
    private final SourceDescriptor descriptor;

    public CourseCollector(SourceSettings settings, RestClient.Builder builder, Clock clock, Resilience resilience, RunInterval interval,
                           String query, List<String> platformSpecs, CandidateReview review) {
        this.clock = clock;
        this.resilience = resilience;
        if (settings.enabled()) {
            resilience.require(POLICY);
        }
        this.interval = interval;
        this.query = query == null ? "" : query.strip();
        this.platforms = platformSpecs.stream().map(Platform::parse).filter(java.util.Objects::nonNull).toList();
        this.review = review;
        this.http = HttpClients.client(builder, settings);
        this.descriptor = new SourceDescriptor(ID, "Tavily course discovery (sent to review)", SourceMode.INDEXED,
            URI.create("https://tavily.com"), URI.create("https://tavily.com/terms"),
            "Course and certification pages found via the Tavily Search API; an admin reviews each before it is catalogued.",
            EnumSet.of(ResourceType.COURSE), settings.timeout(), settings.cacheTtl(),
            settings.enabled() && settings.hasToken() && !this.platforms.isEmpty() && !this.query.isEmpty(), 60);
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
        Map<String, Candidate> byUrl = new LinkedHashMap<>();
        int perPlatform = Math.max(1, Math.min(MAX_RESULTS_PER_PLATFORM, maxItems / Math.max(1, platforms.size())));
        String since = TavilyCollector.startDate(checkpoint);
        for (Platform p : platforms) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("topic", "general");
            body.put("search_depth", "basic");
            body.put("max_results", perPlatform);
            body.put("include_domains", List.of(p.host()));
            if (since != null) {
                body.put("start_date", since);
            } else {
                body.put("time_range", "month");
            }
            JsonNode root = resilience.call(POLICY, () -> HttpClients.getJson(() -> http.post().uri("/search").body(body).retrieve().body(String.class), clock));
            for (JsonNode r : root.path("results")) {
                Candidate c = candidate(r, p);
                if (c != null) {
                    byUrl.putIfAbsent(c.getUrl().getValue(), c);
                }
            }
        }
        review.propose(ID, new ArrayList<>(byUrl.values()));
        // Proposals only: the Scout indexes nothing from this collector.
        return new CollectedBatch(List.of(), null, startedAt.toString());
    }

    /** One Tavily result as a candidate; null when it is off the platform, not about AI or not a course page. */
    static Candidate candidate(JsonNode r, Platform p) {
        String title = HttpClients.text(r, "title");
        CanonicalUrl url = CanonicalUrl.of(HttpClients.text(r, "url"));
        if (title == null || url == null || !p.covers(url)) {
            return null;
        }
        String content = HttpClients.text(r, "content");
        if (!Classification.isAiRelated(title, content, Set.of())) {
            return null;
        }
        String text = title + " " + (content == null ? "" : content) + " " + url.getValue().replace('-', ' ').replace('/', ' ');
        if (!COURSE_WORDS.matcher(text).find()) {
            return null;
        }
        String snippet = content == null ? null : content.length() > 400 ? content.substring(0, 397) + "…" : content;
        return new Candidate(url, title, snippet, p.name());
    }
}
