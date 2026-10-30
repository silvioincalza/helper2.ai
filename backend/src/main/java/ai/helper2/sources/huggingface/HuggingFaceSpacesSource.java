package ai.helper2.sources.huggingface;

import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
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
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Hugging Face Spaces (hosted AI apps and demos). Docs: https://huggingface.co/docs/hub/api */
public class HuggingFaceSpacesSource implements SearchSource {

    private final RestClient http;
    private final Clock clock;
    private final SourceDescriptor descriptor;

    public HuggingFaceSpacesSource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
        this.clock = clock;
        this.http = HttpClients.client(builder, settings);
        this.descriptor = new SourceDescriptor("hf-spaces", "Hugging Face Spaces", SourceMode.LIVE,
            URI.create("https://huggingface.co/spaces"), URI.create("https://huggingface.co/terms-of-service"),
            "AI app and demo metadata from the Hugging Face Hub API", EnumSet.of(ResourceType.PRODUCT),
            settings.timeout(), settings.cacheTtl(), settings.enabled(), 35, false, true, true, true);
    }

    @Override
    public SourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public List<SourceHit> search(SourceQuery query) {
        String text = HttpClients.queryText(query);
        JsonNode root = HttpClients.getJson(() -> http.get()
            .uri(u -> HttpClients.hubListing(u.path("/api/spaces"), query, text).queryParam("limit", Math.min(query.getLimit(), 50)).build())
            .retrieve().body(String.class), clock);
        return HttpClients.hits("hf-spaces", root, clock.instant(), HuggingFaceSpacesSource::toHit);
    }

    private static SourceHit toHit(JsonNode s, Instant now, int position) {
        String id = HttpClients.text(s, "id");
        if (id == null) {
            return null;
        }
        String url = "https://huggingface.co/spaces/" + id;
        Set<String> tags = new LinkedHashSet<>();
        s.path("tags").forEach(t -> {
            if (!t.asString().contains(":")) {
                tags.add(t.asString());
            }
        });
        List<PopularityMetric> pop = s.has("likes")
            ? List.of(new PopularityMetric(PopularityKind.HF_LIKES, s.path("likes").asLong(), "hf-spaces", now))
            : List.of();
        return new SourceHit("hf-spaces", id, URI.create(url), CanonicalUrl.of(url), null, id, null,
            ValueOrigin.SOURCE_PROVIDED, ResourceType.PRODUCT, Audience.BOTH, null, Set.of(), tags, null, null,
            Set.of("web"), null,
            DateFact.fromSource(HttpClients.instant(s, "createdAt"), "hf-spaces", now),
            DateFact.fromSource(HttpClients.instant(s, "lastModified"), "hf-spaces", now), pop, now, position);
    }
}
