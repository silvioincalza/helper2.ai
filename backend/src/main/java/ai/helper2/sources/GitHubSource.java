package ai.helper2.sources;

import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
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
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * GitHub REST search (repositories). Docs: https://docs.github.com/en/rest/search/search
 * Limits: 10 req/min unauthenticated, 30 with a token (search-specific limit).
 */
public class GitHubSource implements SearchSource {

    private final RestClient http;
    private final Clock clock;
    private final SourceDescriptor descriptor;

    public GitHubSource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
        this.clock = clock;
        this.http = HttpClients.gitHubClient(builder, settings);
        this.descriptor = new SourceDescriptor("github", "GitHub", SourceMode.LIVE, URI.create("https://github.com"),
            URI.create("https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"),
            "Repository metadata from the GitHub REST API",
            EnumSet.of(ResourceType.LIBRARY, ResourceType.MCP_SERVER, ResourceType.AGENT, ResourceType.PROMPT, ResourceType.SKILL),
            settings.timeout(), settings.cacheTtl(), settings.enabled(), 20, false, true, true, true);
    }

    @Override
    public SourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public List<SourceHit> search(SourceQuery query) {
        String q = searchText(query);
        // In a window, most-starred first: best match among new repositories favours empty ones named after the words.
        boolean windowed = query.getPublishedAfter() != null;
        JsonNode root = HttpClients.getJson(() -> http.get()
            .uri(u -> {
                u.path("/search/repositories").queryParam("q", q).queryParam("per_page", Math.min(query.getLimit(), 50));
                if (windowed) {
                    u.queryParam("sort", "stars").queryParam("order", "desc");
                }
                return u.build();
            })
            .retrieve().body(String.class), clock);
        return HttpClients.hits(root.path("items"), clock.instant(), GitHubSource::toHit);
    }

    /**
     * The repository search text. A window becomes the documented {@code created:>=} qualifier (creation date, the
     * same date the hit reports as published). Browsing has no subject words, so it keeps an AI scope: GitHub is
     * not an AI-only source, and a date alone returns any new repository.
     */
    static String searchText(SourceQuery query) {
        String words = query.getBrowse()
            ? BROWSE_SCOPE
            : query.getText() + (query.getExpandedTerms().isEmpty() ? "" : " OR " + String.join(" OR ", query.getExpandedTerms()));
        String created = query.getPublishedAfter() == null ? "" : " created:>=" + query.getPublishedAfter();
        return words + " fork:false archived:false" + created;
    }

    /** Words that put a repository in the AI world when no subject was given; at most 5 ORs, GitHub's limit. */
    static final String BROWSE_SCOPE = "llm OR ai-agent OR mcp OR generative-ai OR rag";

    private static SourceHit toHit(JsonNode item, Instant now, int position) {
        String name = HttpClients.text(item, "full_name");
        String htmlUrl = HttpClients.text(item, "html_url");
        CanonicalUrl canonical = CanonicalUrl.of(htmlUrl);
        if (name == null || canonical == null) {
            return null;
        }
        String description = HttpClients.text(item, "description");
        Set<String> topics = new LinkedHashSet<>();
        item.path("topics").forEach(t -> topics.add(t.asString()));
        if (!Classification.isAiRelated(name, description, topics)) {
            return null;
        }
        String spdx = item.path("license").isObject() ? HttpClients.text(item.path("license"), "spdx_id") : null;
        String license = spdx == null ? null : ResultMerger.normalizeLicense(spdx);
        List<PopularityMetric> popularity = item.has("stargazers_count")
            ? List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, item.path("stargazers_count").asLong(), "github", now))
            : List.of();
        return new SourceHit("github", name, URI.create(htmlUrl), canonical, null, name, description,
            ValueOrigin.SOURCE_PROVIDED, Classification.typeOf(name, description, topics, ResourceType.LIBRARY),
            Audience.BUILD_WITH_AI, null, Set.of(), topics, license, null, Set.of(), Classification.openSource(license),
            DateFact.fromSource(HttpClients.instant(item, "created_at"), "github", now),
            // pushed_at = last code push. updated_at changes on stars/metadata and would overstate freshness.
            DateFact.fromSource(HttpClients.instant(item, "pushed_at"), "github", now),
            popularity, now, position);
    }
}
