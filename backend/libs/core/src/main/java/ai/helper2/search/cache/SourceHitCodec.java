package ai.helper2.search.cache;

import ai.helper2.search.CachedHits;
import ai.helper2.search.ListMatch;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.DatePrecision;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
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
import org.apache.commons.lang3.EnumUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class SourceHitCodec {
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
        pop.addObject()
            .put("kind", m.getKind().name())
            .put("value", m.getValue())
            .put("asOf", m.getAsOf().toString());
      }
      n.put("retrievedAt", h.getRetrievedAt().toString());
      n.put("rank", h.getRank());
      date(n, "eventStart", h.getEventStart());
      date(n, "eventEnd", h.getEventEnd());
      n.put("location", h.getLocation());
      if (h.getEventKind() != null) n.put("eventKind", h.getEventKind().name());
      if (h.getEventFormat() != null) n.put("eventFormat", h.getEventFormat().name());
      if (h.getFoundIn() != null) {
        n.putObject("foundIn")
            .put("listTitle", h.getFoundIn().getListTitle())
            .put("headingPath", h.getFoundIn().getHeadingPath());
      }
    }
    return json.writeValueAsString(root);
  }

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
        for (JsonNode p : n.path("popularity")) {
          PopularityKind kind = EnumUtils.getEnum(PopularityKind.class, p.path("kind").asString());
          if (kind != null) {
            pop.add(
                new PopularityMetric(
                    kind,
                    p.path("value").asLong(),
                    sourceId,
                    Instant.parse(p.path("asOf").asString())));
          }
        }
        hits.add(
            new SourceHit(
                    sourceId,
                    n.path("externalId").asString(),
                    URI.create(n.path("url").asString()),
                    CanonicalUrl.of(n.path("canonicalUrl").asString()),
                    n.path("title").asString(),
                    EnumUtils.getEnum(
                        ResourceType.class, n.path("type").asString(), ResourceType.OTHER),
                    Instant.parse(n.path("retrievedAt").asString()))
                .withRepository(CanonicalUrl.of(text(n, "repositoryUrl")))
                .withDescription(
                    text(n, "description"),
                    EnumUtils.getEnum(
                        ValueOrigin.class,
                        n.path("descriptionOrigin").asString(),
                        ValueOrigin.SOURCE_PROVIDED))
                .withAudience(
                    EnumUtils.getEnum(
                        Audience.class, n.path("audience").asString(), Audience.UNKNOWN))
                .withTechLevel(EnumUtils.getEnum(TechLevel.class, text(n, "techLevel")))
                .withUseCases(strings(n.path("useCases")))
                .withTags(strings(n.path("tags")))
                .withLicense(
                    text(n, "license"),
                    n.has("openSource") ? n.path("openSource").asBoolean() : null)
                .withPricing(EnumUtils.getEnum(Pricing.class, text(n, "pricing")))
                .withPlatforms(strings(n.path("platforms")))
                .withPublished(readDate(n.path("published")))
                .withUpdated(readDate(n.path("updated")))
                .withPopularity(pop)
                .withRank(n.path("rank").asInt())
                .withEventDays(readDate(n.path("eventStart")), readDate(n.path("eventEnd")))
                .withLocation(text(n, "location"))
                .withEventKind(EnumUtils.getEnum(EventKind.class, text(n, "eventKind")))
                .withEventFormat(EnumUtils.getEnum(EventFormat.class, text(n, "eventFormat")))
                .withFoundIn(foundIn(n.path("foundIn"))));
      }
      return new CachedHits(hits, Instant.parse(root.path("fetchedAt").asString()));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void date(ObjectNode n, String field, DateFact f) {
    if (f.getValue() != null) {
      n.putObject(field)
          .put("value", f.getValue().toString())
          .put("precision", f.getPrecision().name())
          .put("sourceId", f.getSourceId());
    }
  }

  private static DateFact readDate(JsonNode n) {
    if (n.isMissingNode() || n.isNull()) {
      return DateFact.unknown();
    }
    return new DateFact(
        Instant.parse(n.path("value").asString()),
        EnumUtils.getEnum(
            DatePrecision.class, n.path("precision").asString(), DatePrecision.UNKNOWN),
        n.path("sourceId").asString(),
        List.of(),
        false);
  }

  private static ListMatch foundIn(JsonNode n) {
    if (n.isMissingNode() || n.isNull()) {
      return null;
    }
    return new ListMatch(n.path("listTitle").asString(), n.path("headingPath").asString(""));
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
