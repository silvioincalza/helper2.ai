package ai.helper2.sources.npm;

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
import ai.helper2.sources.support.Classification;
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

public class NpmSource implements SearchSource {
  static final int MAX_TEXT_LENGTH = 64;

  private final RestClient http;
  private final Clock clock;
  private final SourceDescriptor descriptor;

  public NpmSource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
    this.clock = clock;
    this.http = builder.baseUrl(settings.baseUrl()).build();
    this.descriptor =
        new SourceDescriptor(
            "npm",
            "npm registry",
            "npm",
            true,
            SourceMode.LIVE,
            URI.create("https://www.npmjs.com"),
            URI.create("https://docs.npmjs.com/policies/open-source-terms"),
            "Package metadata from the npm registry API",
            EnumSet.of(ResourceType.LIBRARY, ResourceType.MCP_SERVER, ResourceType.AGENT),
            settings.timeout(),
            settings.cacheTtl(),
            settings.enabled(),
            40);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public List<SourceHit> search(SourceQuery query) {
    String text = fitToLimit(query.getKeywords());
    JsonNode root =
        HttpClients.getJson(
            () ->
                http.get()
                    .uri(u ->
                        u.path("/-/v1/search")
                            .queryParam("text", text)
                            .queryParam("size", Math.min(query.getLimit(), 50))
                            .build())
                    .retrieve()
                    .body(String.class),
            clock);
    return HttpClients.hits("npm", root.path("objects"), clock.instant(), NpmSource::toHit);
  }

  static String fitToLimit(String text) {
    if (text.length() <= MAX_TEXT_LENGTH) {
      return text;
    }
    int lastSpace = text.lastIndexOf(' ', MAX_TEXT_LENGTH);
    return lastSpace > 0
        ? text.substring(0, lastSpace).strip()
        : text.substring(0, MAX_TEXT_LENGTH);
  }

  private static SourceHit toHit(JsonNode o, Instant now, int position) {
    JsonNode p = o.path("package");
    String name = HttpClients.text(p, "name");
    if (name == null) {
      return null;
    }
    String url = HttpClients.text(p.path("links"), "npm");
    CanonicalUrl canonical = CanonicalUrl.of(url);
    if (canonical == null) {
      url = "https://www.npmjs.com/package/" + name;
      canonical = CanonicalUrl.of(url);
    }
    if (canonical == null) {
      return null;
    }
    String description = HttpClients.text(p, "description");
    Set<String> keywords = new LinkedHashSet<>();
    p.path("keywords").forEach(k -> keywords.add(k.asString().toLowerCase()));
    if (!Classification.isAiRelated(name, description, keywords)) {
      return null;
    }
    String license =
        HttpClients.text(p, "license") == null
            ? null
            : ResultMerger.normalizeLicense(HttpClients.text(p, "license"));
    List<PopularityMetric> pop =
        o.path("downloads").has("weekly")
            ? List.of(
                new PopularityMetric(
                    PopularityKind.NPM_DOWNLOADS_WEEKLY,
                    o.path("downloads").path("weekly").asLong(),
                    "npm",
                    now))
            : List.of();
    return new SourceHit(
        "npm",
        name,
        HttpClients.link(url, canonical),
        canonical,
        CanonicalUrl.of(HttpClients.text(p.path("links"), "repository")),
        name,
        description,
        ValueOrigin.SOURCE_PROVIDED,
        Classification.typeOf(name, description, keywords, ResourceType.LIBRARY),
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        keywords,
        license,
        null,
        Set.of("node"),
        Classification.openSource(license),
        DateFact.unknown(),
        DateFact.fromSource(HttpClients.instant(p, "date"), "npm", now),
        pop,
        now,
        position);
  }
}
