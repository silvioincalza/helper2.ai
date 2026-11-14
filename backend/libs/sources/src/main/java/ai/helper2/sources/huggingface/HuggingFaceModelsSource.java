package ai.helper2.sources.huggingface;

import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.search.Vitality;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class HuggingFaceModelsSource implements SearchSource {

  private final RestClient http;
  private final Clock clock;
  private final SourceDescriptor descriptor;

  public HuggingFaceModelsSource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
    this.clock = clock;
    this.http = HttpClients.client(builder, settings);
    this.descriptor =
        new SourceDescriptor(
            "hf-models",
            "Hugging Face models",
            "HF models",
            true,
            SourceMode.LIVE,
            URI.create("https://huggingface.co/models"),
            URI.create("https://huggingface.co/terms-of-service"),
            "Model metadata from the Hugging Face Hub API",
            EnumSet.of(ResourceType.MODEL),
            settings.timeout(),
            settings.cacheTtl(),
            settings.enabled(),
            30,
            false,
            true,
            true,
            true);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public List<SourceHit> search(SourceQuery query) {
    Optional<HubTasks.Match> task =
        query.getBrowse() ? Optional.empty() : HubTasks.of(taskText(query));
    List<JsonNode> models =
        task.map(t -> taskModels(query, t))
            .orElseGet(() -> list(query, null, query.getKeywords(), null));
    return HttpClients.hits(
        "hf-models",
        models,
        clock.instant(),
        (item, now, rank) -> toHit(item, "hf-models", now, rank));
  }

  private static String taskText(SourceQuery query) {
    return String.join(
        " ",
        query.getText(),
        Objects.requireNonNullElse(query.getTranslation(), ""),
        query.getKeywords());
  }

  private List<JsonNode> taskModels(SourceQuery query, HubTasks.Match task) {
    String rest = String.join(" ", HubTasks.remaining(words(query), task));
    List<JsonNode> found = list(query, task.pipelineTag(), rest, "likes");
    if (rest.isBlank() || isFull(found, query)) {
      return found;
    }
    return merged(query, found, list(query, task.pipelineTag(), "", "likes"));
  }

  private static boolean isFull(List<JsonNode> found, SourceQuery query) {
    return found.size() * 2 >= query.getLimit();
  }

  private static List<JsonNode> merged(
      SourceQuery query, List<JsonNode> first, List<JsonNode> more) {
    Map<String, JsonNode> byId = new LinkedHashMap<>();
    for (JsonNode m : first) {
      byId.putIfAbsent(HttpClients.text(m, "id"), m);
    }
    for (JsonNode m : more) {
      byId.putIfAbsent(HttpClients.text(m, "id"), m);
    }
    return byId.values().stream().limit(query.getLimit()).toList();
  }

  private static List<String> words(SourceQuery query) {
    List<String> words =
        query.getSearchTerms().isEmpty()
            ? List.of(query.getKeywords().split(" "))
            : query.getSearchTerms();
    return words.stream().filter(w -> !w.isBlank()).toList();
  }

  private List<JsonNode> list(SourceQuery query, String pipelineTag, String text, String sort) {
    JsonNode root =
        HttpClients.getJson(
            () ->
                http.get()
                    .uri(u -> {
                      HttpClients.hubListing(u.path("/api/models"), query, text)
                          .queryParam("limit", Math.min(query.getLimit(), 50));
                      EXPANDED_FIELDS.forEach(f -> u.queryParam("expand[]", f));
                      if (pipelineTag != null) {
                        u.queryParam("pipeline_tag", pipelineTag);
                      }
                      if (sort != null && query.getPublishedAfter() == null && !query.getBrowse()) {
                        u.queryParam("sort", sort);
                      }
                      return u.build();
                    })
                    .retrieve()
                    .body(String.class),
            clock);
    List<JsonNode> out = new ArrayList<>();
    root.forEach(out::add);
    return out;
  }

  public static SourceHit toHit(JsonNode m, String sourceId, Instant now, int position) {
    return toHit(m, sourceId, now, position, Set.of());
  }

  static final List<String> EXPANDED_FIELDS =
      List.of(
          "downloads",
          "likes",
          "tags",
          "createdAt",
          "lastModified",
          "pipeline_tag",
          "library_name",
          "disabled",
          "gated");

  private static Vitality vitalityOf(JsonNode model) {
    return model.has("disabled")
        ? new Vitality(null, null, model.path("disabled").asBoolean(false))
        : Vitality.UNKNOWN;
  }

  public static SourceHit toHit(
      JsonNode m, String sourceId, Instant now, int position, Set<String> extraTags) {
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
      pop.add(
          new PopularityMetric(PopularityKind.HF_LIKES, m.path("likes").asLong(), sourceId, now));
    }
    if (m.has("downloads")) {
      pop.add(
          new PopularityMetric(
              PopularityKind.HF_DOWNLOADS, m.path("downloads").asLong(), sourceId, now));
    }
    return new SourceHit(
            sourceId,
            id,
            URI.create(canonical.getValue()),
            canonical,
            null,
            id,
            null,
            ValueOrigin.SOURCE_PROVIDED,
            ResourceType.MODEL,
            Audience.BUILD_WITH_AI,
            null,
            Set.of(),
            tags,
            license,
            null,
            Set.of(),
            Classification.openSource(license),
            DateFact.fromSource(HttpClients.instant(m, "createdAt"), sourceId, now),
            DateFact.fromSource(HttpClients.instant(m, "lastModified"), sourceId, now),
            pop,
            now,
            position)
        .withVitality(vitalityOf(m));
  }
}
