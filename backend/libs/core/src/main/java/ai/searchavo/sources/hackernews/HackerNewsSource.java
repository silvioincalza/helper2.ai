package ai.searchavo.sources.hackernews;

import ai.searchavo.search.SearchSource;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceHit;
import ai.searchavo.search.SourceQuery;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.SourceMode;
import ai.searchavo.sources.support.Classification;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

public class HackerNewsSource implements SearchSource {

  private final RestClient http;
  private final Clock clock;
  private final SourceDescriptor descriptor;
  private final ShadowLibraries shadowLibraries;

  public HackerNewsSource(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      ShadowLibraries shadowLibraries) {
    this.clock = clock;
    this.shadowLibraries = shadowLibraries;
    this.http = HttpClients.client(builder, settings);
    this.descriptor =
        SourceDescriptor.external(
                "hacker-news",
                "Hacker News",
                "HN",
                SourceMode.LIVE,
                URI.create("https://news.ycombinator.com"),
                EnumSet.of(ResourceType.NEWS),
                settings.timeout(),
                settings.cacheTtl())
            .withTerms(URI.create("https://www.ycombinator.com/legal/"))
            .withAttribution("Story titles and links from the Hacker News search API by Algolia")
            .enabledWhen(settings.enabled())
            .withPriority(47)
            .filteringByDate();
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public List<SourceHit> search(SourceQuery query) {
    String text = query.getKeywords();
    if (text.isBlank()) {
      return List.of();
    }
    JsonNode root =
        HttpClients.getJson(
            () -> http.get().uri(u -> storySearch(u, query, text)).retrieve().body(String.class),
            clock);
    return firstHits(
        HttpClients.hits(
            "hacker-news", aiStories(root.path("hits")), clock.instant(), HackerNewsSource::toHit),
        query);
  }

  private static List<SourceHit> firstHits(List<SourceHit> hits, SourceQuery query) {
    return hits.size() <= query.getLimit() ? hits : List.copyOf(hits.subList(0, query.getLimit()));
  }

  private static int fetchSize(SourceQuery query) {
    return Math.min(query.getLimit() * 3, 50);
  }

  private static URI storySearch(UriBuilder u, SourceQuery query, String text) {
    u.path("/api/v1/search")
        .queryParam("query", text)
        .queryParam("tags", "story")
        .queryParam("hitsPerPage", fetchSize(query));
    if (query.getPublishedAfter() != null) {
      u.queryParam("numericFilters", "created_at_i>=" + epochSecond(query.getPublishedAfter()));
    }
    return u.build();
  }

  static long epochSecond(LocalDate day) {
    return day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
  }

  private List<JsonNode> aiStories(JsonNode hits) {
    List<JsonNode> kept = new ArrayList<>();
    for (JsonNode h : hits) {
      CanonicalUrl link = CanonicalUrl.of(HttpClients.text(h, "url"));
      if (link == null) {
        HttpClients.dropped("hacker-news", "no-link");
      } else if (shadowLibraries.isShadowLibrary(link.getHost())) {
        HttpClients.dropped("hacker-news", "shadow-library");
      } else if (!Classification.isAiRelated(HttpClients.text(h, "title"), null, Set.of())) {
        HttpClients.dropped("hacker-news", "not-ai");
      } else {
        kept.add(h);
      }
    }
    return kept;
  }

  private static SourceHit toHit(JsonNode h, Instant now, int position) {
    String title = HttpClients.text(h, "title");
    String url = HttpClients.text(h, "url");
    CanonicalUrl canonical = CanonicalUrl.of(url);
    if (title == null || canonical == null) {
      return null;
    }
    String id = HttpClients.text(h, "objectID");
    return new SourceHit(
            "hacker-news",
            id == null ? url : id,
            HttpClients.link(url, canonical),
            canonical,
            title,
            ResourceType.NEWS,
            now)
        .withAudience(Audience.BOTH)
        .withPublished(
            DateFact.fromSource(HttpClients.instant(h, "created_at"), "hacker-news", now))
        .withRank(position);
  }
}
