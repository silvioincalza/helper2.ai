package ai.helper2.search.cache;

import ai.helper2.search.CachedHits;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.DatePrecision;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.TechLevel;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Explicit, versioned JSON form of cached hits. Keeps Jackson out of the domain. */
final class SourceHitCodec {

    /** Bumped when the shape changes, so an entry written in another shape is a cache miss. */
    static final int VERSION = 1;
    private final ObjectMapper json;

    SourceHitCodec(ObjectMapper json) {
        this.json = json;
    }

    String encode(CachedHits value) {
        ObjectNode root = json.createObjectNode();
        root.put("v", VERSION);
        root.put("fetchedAt", value.getFetchedAt().toString());
        ArrayNode arr = root.putArray("hits");
        for (SourceHit h : value.getHits()) {
            ObjectNode n = arr.addObject();
            n.put("sourceId", h.getSourceId());
            n.put("externalId", h.getExternalId());
            n.put("url", h.getUrl().toString());
            n.put("canonicalUrl", h.getCanonicalUrl().getValue());
            n.put("repositoryUrl", h.getRepositoryUrl() == null ? null : h.getRepositoryUrl().getValue());
            n.put("title", h.getTitle());
            n.put("description", h.getDescription());
            n.put("descriptionOrigin", h.getDescriptionOrigin().name());
            n.put("type", h.getType().name());
            n.put("audience", h.getAudience().name());
            n.put("techLevel", h.getTechLevel() == null ? null : h.getTechLevel().name());
            n.putPOJO("useCases", h.getUseCases());
            n.putPOJO("tags", h.getTags());
            n.put("license", h.getLicense());
            n.put("pricing", h.getPricing() == null ? null : h.getPricing().name());
            n.putPOJO("platforms", h.getPlatforms());
            if (h.getOpenSource() != null) {
                n.put("openSource", h.getOpenSource());
            }
            date(n, "published", h.getPublished());
            date(n, "updated", h.getUpdated());
            ArrayNode pop = n.putArray("popularity");
            for (PopularityMetric m : h.getPopularity()) {
                pop.addObject().put("kind", m.getKind().name()).put("value", m.getValue()).put("asOf", m.getAsOf().toString());
            }
            n.put("retrievedAt", h.getRetrievedAt().toString());
            n.put("rank", h.getRank());
            date(n, "eventStart", h.getEventStart());
            date(n, "eventEnd", h.getEventEnd());
            n.put("location", h.getLocation());
            if (h.getEventKind() != null) n.put("eventKind", h.getEventKind().name());
            if (h.getEventFormat() != null) n.put("eventFormat", h.getEventFormat().name());
        }
        return json.writeValueAsString(root);
    }

    /** Returns null for unknown versions or corrupt entries (treated as a cache miss). */
    CachedHits decode(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root.path("v").asInt() != VERSION) {
                return null;
            }
            List<SourceHit> hits = new ArrayList<>();
            for (JsonNode n : root.path("hits")) {
                String sourceId = n.path("sourceId").asString();
                List<PopularityMetric> pop = new ArrayList<>();
                n.path("popularity").forEach(p -> pop.add(new PopularityMetric(
                    PopularityKind.valueOf(p.path("kind").asString()), p.path("value").asLong(), sourceId,
                    Instant.parse(p.path("asOf").asString()))));
                hits.add(new SourceHit(
                    sourceId, n.path("externalId").asString(), URI.create(n.path("url").asString()),
                    CanonicalUrl.of(n.path("canonicalUrl").asString()), CanonicalUrl.of(text(n, "repositoryUrl")),
                    n.path("title").asString(), text(n, "description"),
                    ValueOrigin.valueOf(n.path("descriptionOrigin").asString()),
                    ResourceType.valueOf(n.path("type").asString()), Audience.valueOf(n.path("audience").asString()),
                    text(n, "techLevel") == null ? null : TechLevel.valueOf(text(n, "techLevel")),
                    strings(n.path("useCases")), strings(n.path("tags")), text(n, "license"),
                    text(n, "pricing") == null ? null : Pricing.valueOf(text(n, "pricing")),
                    strings(n.path("platforms")), n.has("openSource") ? n.path("openSource").asBoolean() : null,
                    readDate(n.path("published")), readDate(n.path("updated")), pop,
                    Instant.parse(n.path("retrievedAt").asString()), n.path("rank").asInt(),
                    readDate(n.path("eventStart")), readDate(n.path("eventEnd")), text(n, "location"),
                    text(n, "eventKind") == null ? null : EventKind.valueOf(text(n, "eventKind")),
                    text(n, "eventFormat") == null ? null : EventFormat.valueOf(text(n, "eventFormat"))));
            }
            return new CachedHits(hits, Instant.parse(root.path("fetchedAt").asString()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void date(ObjectNode n, String field, DateFact f) {
        if (f.getValue() != null) {
            n.putObject(field).put("value", f.getValue().toString()).put("precision", f.getPrecision().name())
                .put("sourceId", f.getSourceId());
        }
    }

    private static DateFact readDate(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) {
            return DateFact.unknown();
        }
        return new DateFact(Instant.parse(n.path("value").asString()), DatePrecision.valueOf(n.path("precision").asString()),
            n.path("sourceId").asString(), List.of(), false);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static Set<String> strings(JsonNode arr) {
        Set<String> out = new LinkedHashSet<>();
        arr.forEach(x -> out.add(x.asString()));
        return out;
    }
}
