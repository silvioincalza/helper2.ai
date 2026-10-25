package ai.helper2.sources;

import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Hugging Face Hub models sorted by trending score, filtered by a minimum like count. The Hub has
 * millions of models, so this is a bounded "what is new and getting attention" window, not a full
 * sync. Not incremental: each due pass reads the current top list again; the catalog's fingerprints
 * make unchanged models a no-op. Categorization is deterministic (type MODEL, pipeline tag, licence
 * tag); the Hub list API returns no description, so these models are not sent to LLM enrichment.
 * Docs: https://huggingface.co/docs/hub/api
 */
public class HuggingFaceTrendingCollector implements Collector {

    static final String ID = "hf-trending";
    private static final int MAX_PAGE = 100;

    private final RestClient http;
    private final Clock clock;
    private final RunInterval interval;
    private final int minLikes;
    private final SourceDescriptor descriptor;

    public HuggingFaceTrendingCollector(SourceSettings settings, RestClient.Builder builder, Clock clock,
                                        RunInterval interval, int minLikes) {
        this.clock = clock;
        this.interval = interval;
        this.minLikes = minLikes;
        this.http = HttpClients.client(builder, settings);
        this.descriptor = new SourceDescriptor(ID, "Hugging Face trending models", SourceMode.INDEXED,
            URI.create("https://huggingface.co/models?sort=trending"), URI.create("https://huggingface.co/terms-of-service"),
            "Model metadata from the Hugging Face Hub API", EnumSet.of(ResourceType.MODEL),
            settings.timeout(), settings.cacheTtl(), settings.enabled(), 30);
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
        int limit = Math.max(1, Math.min(maxItems, MAX_PAGE));
        JsonNode root = HttpClients.getJson(() -> http.get()
            .uri(u -> u.path("/api/models").queryParam("sort", "trendingScore").queryParam("direction", -1)
                .queryParam("limit", limit).build())
            .retrieve().body(String.class), clock);
        List<SourceHit> hits = new ArrayList<>();
        int rank = 0;
        for (JsonNode m : root) {
            if (m.path("likes").asLong(0) < minLikes) {
                continue;
            }
            SourceHit hit = HuggingFaceModelsSource.toHit(m, ID, startedAt, rank++);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return new CollectedBatch(hits, null, startedAt.toString());
    }
}
