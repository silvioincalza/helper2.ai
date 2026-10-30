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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Hugging Face Hub model search. Docs: https://huggingface.co/docs/hub/api */
public class HuggingFaceModelsSource implements SearchSource {

    private final RestClient http;
    private final Clock clock;
    private final SourceDescriptor descriptor;

    public HuggingFaceModelsSource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
        this.clock = clock;
        this.http = HttpClients.client(builder, settings);
        this.descriptor = new SourceDescriptor("hf-models", "Hugging Face models", SourceMode.LIVE,
            URI.create("https://huggingface.co/models"), URI.create("https://huggingface.co/terms-of-service"),
            "Model metadata from the Hugging Face Hub API", EnumSet.of(ResourceType.MODEL),
            settings.timeout(), settings.cacheTtl(), settings.enabled(), 30, false, true, true, true);
    }

    @Override
    public SourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public List<SourceHit> search(SourceQuery query) {
        String text = HttpClients.queryText(query);
        JsonNode root = HttpClients.getJson(() -> http.get()
            .uri(u -> HttpClients.hubListing(u.path("/api/models"), query, text).queryParam("limit", Math.min(query.getLimit(), 50)).build())
            .retrieve().body(String.class), clock);
        return HttpClients.hits("hf-models", root, clock.instant(), (item, now, rank) -> toHit(item, "hf-models", now, rank));
    }

    /** Maps one Hub API model entry; shared with the Scout's trending collector. null when its id is no valid Hub path. */
    static SourceHit toHit(JsonNode m, String sourceId, Instant now, int position) {
        return toHit(m, sourceId, now, position, Set.of());
    }

    /** As above, plus tags the caller knows (e.g. the provider organisation it listed). */
    static SourceHit toHit(JsonNode m, String sourceId, Instant now, int position, Set<String> extraTags) {
        String id = HttpClients.text(m, "id");
        if (id == null) {
            return null;
        }
        CanonicalUrl canonical = CanonicalUrl.of("https://huggingface.co/" + id);
        if (canonical == null) {
            return null;
        }
        Set<String> tags = new LinkedHashSet<>(extraTags);
        String license = null;
        for (JsonNode t : m.path("tags")) {
            String tag = t.asString();
            if (tag.startsWith("license:")) {
                license = ResultMerger.normalizeLicense(tag.substring("license:".length()));
            } else if (!tag.contains(":")) {
                tags.add(tag);
            }
        }
        String pipeline = HttpClients.text(m, "pipeline_tag");
        if (pipeline != null) {
            tags.add(pipeline);
        }
        List<PopularityMetric> pop = new ArrayList<>();
        if (m.has("likes")) {
            pop.add(new PopularityMetric(PopularityKind.HF_LIKES, m.path("likes").asLong(), sourceId, now));
        }
        if (m.has("downloads")) {
            pop.add(new PopularityMetric(PopularityKind.HF_DOWNLOADS, m.path("downloads").asLong(), sourceId, now));
        }
        return new SourceHit(sourceId, id, URI.create(canonical.getValue()), canonical, null, id, null,
            ValueOrigin.SOURCE_PROVIDED, ResourceType.MODEL, Audience.BUILD_WITH_AI, null, Set.of(), tags, license,
            null, Set.of(), Classification.openSource(license),
            DateFact.fromSource(HttpClients.instant(m, "createdAt"), sourceId, now),
            DateFact.fromSource(HttpClients.instant(m, "lastModified"), sourceId, now), pop, now, position);
    }
}
