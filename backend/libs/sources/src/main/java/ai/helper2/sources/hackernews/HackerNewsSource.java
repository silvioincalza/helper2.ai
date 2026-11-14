package ai.helper2.sources.hackernews;

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

  public HackerNewsSource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
    this.clock = clock;
    this.http = HttpClients.client(builder, settings);
    this.descriptor =
        new SourceDescriptor(
            "hacker-news",
            "Hacker News",
            "HN",
            true,
            SourceMode.LIVE,
            URI.create("https://news.ycombinator.com"),
            URI.create("https://www.ycombinator.com/legal/"),
            "Story titles and links from the Hacker News search API by Algolia",
            EnumSet.of(ResourceType.NEWS),
            settings.timeout(),
            settings.cacheTtl(),
            settings.enabled(),
            47,
            false,
            true,
            true);
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
    return HttpClients.firstHits(
        HttpClients.hits(
            "hacker-news", aiStories(root.path("hits")), clock.instant(), HackerNewsSource::toHit),
        query);
  }

  private static URI storySearch(UriBuilder u, SourceQuery query, String text) {
    u.path("/api/v1/search")
        .queryParam("query", text)
        .queryParam("tags", "story")
        .queryParam("hitsPerPage", HttpClients.fetchSize(query));
    if (query.getPublishedAfter() != null) {
      u.queryParam("numericFilters", "created_at_i>=" + epochSecond(query.getPublishedAfter()));
    }
    return u.build();
  }

  static long epochSecond(LocalDate day) {
    return day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
  }

  static List<JsonNode> aiStories(JsonNode hits) {
    List<JsonNode> kept = new ArrayList<>();
    for (JsonNode h : hits) {
      if (CanonicalUrl.of(HttpClients.text(h, "url")) == null) {
        HttpClients.dropped("hacker-news", "no-link");
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
        null,
        title,
        null,
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.NEWS,
        Audience.BOTH,
        null,
        Set.of(),
        Set.of(),
        null,
        null,
        Set.of(),
        null,
        DateFact.fromSource(HttpClients.instant(h, "created_at"), "hacker-news", now),
        DateFact.unknown(),
        List.of(),
        now,
        position);
  }
}
