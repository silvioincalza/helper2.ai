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
import java.util.Set;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * The newest models published by the official Hugging Face organisations of AI providers
 * (meta-llama, nvidia, mistralai, deepseek-ai, Qwen, google, openai, xai-org...). This catches
 * open-weight releases the day they appear, whether or not they trend yet. One Hub call per
 * organisation, newest first; categorisation is deterministic, as in {@link HuggingFaceTrendingCollector}.
 * The organisation name is added as a tag so the provider is visible and searchable.
 * Docs: https://huggingface.co/docs/hub/api
 */
public class HuggingFaceProviderOrgsCollector implements Collector {

    static final String ID = "hf-provider-orgs";

    private final RestClient http;
    private final Clock clock;
    private final RunInterval interval;
    private final List<String> orgs;
    private final int perOrg;
    private final SourceDescriptor descriptor;

    public HuggingFaceProviderOrgsCollector(SourceSettings settings, RestClient.Builder builder, Clock clock,
                                            RunInterval interval, List<String> orgs, int perOrg) {
        this.clock = clock;
        this.interval = interval;
        this.orgs = List.copyOf(orgs);
        this.perOrg = Math.max(1, Math.min(perOrg, 100));
        this.http = HttpClients.client(builder, settings);
        this.descriptor = new SourceDescriptor(ID, "Hugging Face provider organisations", SourceMode.INDEXED,
            URI.create("https://huggingface.co/models"), URI.create("https://huggingface.co/terms-of-service"),
            "Newest models of AI providers' official Hugging Face organisations: " + String.join(", ", orgs),
            EnumSet.of(ResourceType.MODEL), settings.timeout(), settings.cacheTtl(), settings.enabled(), 30);
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
        List<SourceHit> hits = new ArrayList<>();
        int rank = 0;
        for (String org : orgs) {
            if (hits.size() >= maxItems) {
                break;
            }
            JsonNode root = HttpClients.getJson(() -> http.get()
                .uri(u -> u.path("/api/models").queryParam("author", org).queryParam("sort", "createdAt")
                    .queryParam("direction", -1).queryParam("limit", perOrg).build())
                .retrieve().body(String.class), clock);
            for (JsonNode m : root) {
                SourceHit hit = HuggingFaceModelsSource.toHit(m, ID, startedAt, rank++, Set.of(org.toLowerCase()));
                if (hit != null && hits.size() < maxItems) {
                    hits.add(hit);
                }
            }
        }
        return new CollectedBatch(hits, null, startedAt.toString());
    }
}
