package ai.helper2.sources.huggingface;

import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.search.Vitality;
import ai.helper2.search.VitalityPolicy;
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
import java.util.Optional;
import java.util.Set;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class HuggingFaceSpacesSource implements SearchSource {

  private final RestClient http;
  private final Clock clock;
  private final VitalityPolicy vitality;
  private final SourceDescriptor descriptor;

  public HuggingFaceSpacesSource(
      SourceSettings settings, RestClient.Builder builder, Clock clock, VitalityPolicy vitality) {
    this.clock = clock;
    this.vitality = vitality;
    this.http = HttpClients.client(builder, settings);
    this.descriptor =
        new SourceDescriptor(
            "hf-spaces",
            "Hugging Face Spaces",
            "Spaces",
            true,
            SourceMode.LIVE,
            URI.create("https://huggingface.co/spaces"),
            URI.create("https://huggingface.co/terms-of-service"),
            "AI app and demo metadata from the Hugging Face Hub API",
            EnumSet.of(ResourceType.PRODUCT),
            settings.timeout(),
            settings.cacheTtl(),
            settings.enabled(),
            35,
            false,
            true,
            true,
            true,
            false,
            null,
            SPACE_URL_PREFIX);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public List<SourceHit> search(SourceQuery query) {
    if (!describesANeed(query)) {
      return listed(query);
    }
    List<SourceHit> byMeaning = foundByMeaning(query);
    return hasAResult(byMeaning) ? byMeaning : listed(query);
  }

  private boolean hasAResult(List<SourceHit> hits) {
    return hits.stream().anyMatch(hit -> vitality.refusalOf(hit) == null);
  }

  private static boolean describesANeed(SourceQuery query) {
    return !query.getBrowse() && query.getPublishedAfter() == null && !meaningOf(query).isBlank();
  }

  private static String meaningOf(SourceQuery query) {
    String sentence = query.getTranslation() == null ? query.getText() : query.getTranslation();
    String trimmed = sentence.strip();
    return trimmed.length() <= MAX_MEANING_CHARS
        ? trimmed
        : trimmed.substring(0, MAX_MEANING_CHARS);
  }

  private List<SourceHit> foundByMeaning(SourceQuery query) {
    String meaning = meaningOf(query);
    try {
      JsonNode root =
          HttpClients.getJson(
              () ->
                  http.get()
                      .uri(u ->
                          u.path("/api/spaces/semantic-search")
                              .queryParam("q", meaning)
                              .queryParamIfPresent("category", SpaceCategories.of(meaning))
                              .build())
                      .retrieve()
                      .body(String.class),
              clock);
      return HttpClients.hits("hf-spaces", root, clock.instant(), HuggingFaceSpacesSource::toHit);
    } catch (SourceFailure.Unavailable e) {
      HttpClients.dropped("hf-spaces", "search-by-meaning-unavailable");
      return List.of();
    }
  }

  private List<SourceHit> listed(SourceQuery query) {
    String text = query.getKeywords();
    JsonNode root =
        HttpClients.getJson(
            () ->
                http.get()
                    .uri(u -> {
                      HttpClients.hubListing(u.path("/api/spaces"), query, text)
                          .queryParamIfPresent("sort", mostLikedFirst(query))
                          .queryParam("direction", -1)
                          .queryParam(
                              "limit", Math.min(query.getLimit() * FETCH_FACTOR, MAX_FETCH));
                      EXPANDED_FIELDS.forEach(f -> u.queryParam("expand[]", f));
                      return u.build();
                    })
                    .retrieve()
                    .body(String.class),
            clock);
    return HttpClients.hits("hf-spaces", root, clock.instant(), HuggingFaceSpacesSource::toHit);
  }

  private static Optional<String> mostLikedFirst(SourceQuery query) {
    return query.getPublishedAfter() == null ? Optional.of("likes") : Optional.empty();
  }

  private static Vitality vitalityOf(JsonNode space) {
    String stage = HttpClients.text(space.path("runtime"), "stage");
    return new Vitality(null, null, null, stage != null && RUNNING_STAGES.contains(stage));
  }

  static final Set<String> RUNNING_STAGES = Set.of("RUNNING", "RUNNING_BUILDING");

  static final String SPACE_URL_PREFIX = "https://huggingface.co/spaces/";

  static final List<String> EXPANDED_FIELDS =
      List.of("runtime", "likes", "tags", "createdAt", "lastModified");

  static final int FETCH_FACTOR = 3;

  static final int MAX_MEANING_CHARS = 250;

  static final int MAX_FETCH = 100;

  private static SourceHit toHit(JsonNode s, Instant now, int position) {
    String id = HttpClients.text(s, "id");
    if (id == null) {
      return null;
    }
    String url = SPACE_URL_PREFIX + id;
    Set<String> tags = new LinkedHashSet<>();
    s.path("tags")
        .forEach(t -> {
          if (!t.asString().contains(":")) {
            tags.add(t.asString());
          }
        });
    List<PopularityMetric> pop =
        s.has("likes")
            ? List.of(
                new PopularityMetric(
                    PopularityKind.HF_LIKES, s.path("likes").asLong(), "hf-spaces", now))
            : List.of();
    return new SourceHit(
            "hf-spaces",
            id,
            URI.create(url),
            CanonicalUrl.of(url),
            null,
            id,
            null,
            ValueOrigin.SOURCE_PROVIDED,
            ResourceType.PRODUCT,
            Audience.BOTH,
            null,
            Set.of(),
            tags,
            null,
            null,
            Set.of("web"),
            null,
            DateFact.fromSource(HttpClients.instant(s, "createdAt"), "hf-spaces", now),
            DateFact.fromSource(HttpClients.instant(s, "lastModified"), "hf-spaces", now),
            pop,
            now,
            position)
        .withVitality(vitalityOf(s));
  }
}
