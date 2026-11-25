package ai.searchavo.sources.huggingface;

import ai.searchavo.search.PopularityKind;
import ai.searchavo.search.PopularityMetric;
import ai.searchavo.search.SearchSource;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.search.SourceHit;
import ai.searchavo.search.SourceQuery;
import ai.searchavo.search.Vitality;
import ai.searchavo.search.VitalityPolicy;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.SourceMode;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.SourceSettings;
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
        SourceDescriptor.external(
                "hf-spaces",
                "Hugging Face Spaces",
                "Spaces",
                SourceMode.LIVE,
                URI.create("https://huggingface.co/spaces"),
                EnumSet.of(ResourceType.PRODUCT),
                settings.timeout(),
                settings.cacheTtl())
            .withTerms(URI.create("https://huggingface.co/terms-of-service"))
            .withAttribution("AI app and demo metadata from the Hugging Face Hub API")
            .enabledWhen(settings.enabled())
            .withPriority(35)
            .filteringByDate()
            .browsing()
            .confirmingUrlsUnder(SPACE_URL_PREFIX);
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
                      HubListing.of(u.path("/api/spaces"), query, text)
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
            "hf-spaces", id, URI.create(url), CanonicalUrl.of(url), id, ResourceType.PRODUCT, now)
        .withAudience(Audience.BOTH)
        .withTags(tags)
        .withPlatforms(Set.of("web"))
        .withPublished(DateFact.fromSource(HttpClients.instant(s, "createdAt"), "hf-spaces", now))
        .withUpdated(DateFact.fromSource(HttpClients.instant(s, "lastModified"), "hf-spaces", now))
        .withPopularity(pop)
        .withRank(position)
        .withVitality(vitalityOf(s));
  }
}
