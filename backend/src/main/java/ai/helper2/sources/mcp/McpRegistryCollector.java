package ai.helper2.sources.mcp;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Official MCP Registry (CC0 data; registry asks aggregators to sync ~hourly into their own store).
 * Collected by the Scout and served from the Helper2 index — not queried per user search.
 * Docs: https://registry.modelcontextprotocol.io/docs
 */
public class McpRegistryCollector implements Collector {

    private static final String META = "io.modelcontextprotocol.registry/official";

    /** Circuit breaker and retry of this collector's calls (ADR 0033). */
    static final String POLICY = "collector-mcp-registry";

    private final RestClient http;
    private final Clock clock;
    private final Resilience resilience;
    private final String baseUrl;
    private final SourceDescriptor descriptor;
    private final RunInterval interval;

    public McpRegistryCollector(SourceSettings settings, RestClient.Builder builder, Clock clock, Resilience resilience) {
        this(settings, builder, clock, resilience, new RunInterval(Duration.ZERO));
    }

    /** [interval]: the registry asks aggregators to poll "on a regular but infrequent basis (e.g., once per hour)". */
    public McpRegistryCollector(SourceSettings settings, RestClient.Builder builder, Clock clock, Resilience resilience, RunInterval interval) {
        this.clock = clock;
        this.resilience = resilience;
        if (settings.enabled()) {
            resilience.require(POLICY);
        }
        this.interval = interval;
        this.baseUrl = settings.baseUrl();
        this.http = builder.baseUrl(settings.baseUrl()).build();
        this.descriptor = new SourceDescriptor("mcp-registry", "Official MCP Registry", SourceMode.INDEXED,
            URI.create("https://registry.modelcontextprotocol.io"),
            URI.create("https://github.com/modelcontextprotocol/registry"),
            "This data comes from the Official MCP Registry (CC0).", EnumSet.of(ResourceType.MCP_SERVER),
            settings.timeout(), settings.cacheTtl(), settings.enabled(), 25);
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
        Map<String, SourceHit> latest = new LinkedHashMap<>();
        String cursor = resumeCursor;
        int pages = 0;
        do {
            String c = cursor;
            JsonNode root = resilience.call(POLICY, () -> HttpClients.getJson(() -> http.get().uri(u -> {
                var b = u.path("/v0.1/servers").queryParam("limit", 100);
                if (checkpoint != null) {
                    b = b.queryParam("updated_since", checkpoint);
                }
                if (c != null) {
                    b = b.queryParam("cursor", c);
                }
                return b.build();
            }).retrieve().body(String.class), clock));
            for (JsonNode entry : root.path("servers")) {
                JsonNode meta = entry.path("_meta").path(META);
                if (!meta.path("isLatest").asBoolean(false)) {
                    continue;
                }
                SourceHit hit = toHit(entry.path("server"), meta, startedAt, latest.size());
                if (hit != null) {
                    latest.put(hit.getExternalId(), hit);
                }
            }
            cursor = HttpClients.text(root.path("metadata"), "nextCursor");
            pages++;
        } while (cursor != null && latest.size() < maxItems && pages < 100);
        List<SourceHit> hits = new ArrayList<>(latest.values());
        boolean complete = cursor == null;
        return new CollectedBatch(hits, complete ? null : cursor, complete ? startedAt.toString() : null);
    }

    private SourceHit toHit(JsonNode s, JsonNode meta, Instant now, int rank) {
        String name = HttpClients.text(s, "name");
        if (name == null) {
            return null;
        }
        String repo = HttpClients.text(s.path("repository"), "url");
        String website = HttpClients.text(s, "websiteUrl");
        String registryEntry = baseUrl + "/v0.1/servers/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/versions/latest";
        String link = repo != null && CanonicalUrl.of(repo) != null ? repo
            : website != null && CanonicalUrl.of(website) != null ? website : registryEntry;
        CanonicalUrl canonical = CanonicalUrl.of(link);
        if (canonical == null) {
            return null;
        }
        String title = HttpClients.text(s, "title");
        Set<String> platforms = s.path("remotes").isEmpty() ? Set.of("local") : Set.of("remote");
        return new SourceHit("mcp-registry", name, HttpClients.link(link, canonical), canonical, CanonicalUrl.of(repo),
            title == null ? name : title, HttpClients.text(s, "description"), ValueOrigin.SOURCE_PROVIDED,
            ResourceType.MCP_SERVER, Audience.BUILD_WITH_AI, null, Set.of(), Set.of("mcp"), null, null, platforms, null,
            DateFact.fromSource(HttpClients.instant(meta, "publishedAt"), "mcp-registry", now),
            DateFact.fromSource(HttpClients.instant(meta, "updatedAt"), "mcp-registry", now), List.of(), now, rank);
    }
}
