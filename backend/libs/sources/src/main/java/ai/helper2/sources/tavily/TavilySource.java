package ai.helper2.sources.tavily;

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
import ai.helper2.shared.WebPageTitle;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class TavilySource implements SearchSource {

  private static final Pattern EVENT =
      Pattern.compile(
          "\\b(conference|summit|meetup|hackathon|webinar|workshop|expo|symposium|keynote|devday|event)\\b",
          Pattern.CASE_INSENSITIVE);

  private final RestClient http;
  private final Clock clock;
  private final SourceDescriptor descriptor;

  public TavilySource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
    this.clock = clock;
    this.http = HttpClients.client(builder, settings);
    this.descriptor =
        new SourceDescriptor(
            "live-web",
            "Live web search",
            "Web",
            true,
            SourceMode.LIVE,
            URI.create("https://helper2.ai/sources"),
            URI.create("https://helper2.ai/terms"),
            "Web results found by a live web search; each links to the original publisher.",
            EnumSet.of(ResourceType.NEWS, ResourceType.ARTICLE, ResourceType.EVENT),
            settings.timeout(),
            Duration.ZERO,
            settings.enabled() && settings.hasToken(),
            48,
            true,
            true,
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
    Map<String, Object> body =
        new LinkedHashMap<>(
            Map.of(
                "query", queryText(query),
                "topic", topic(query),
                "search_depth", "basic",
                "max_results", Math.min(query.getLimit(), 20)));
    if (query.getPublishedAfter() != null) {
      body.put("start_date", query.getPublishedAfter().toString());
    }
    JsonNode root =
        HttpClients.getJson(
            () -> http.post().uri("/search").body(body).retrieve().body(String.class), clock);
    return HttpClients.hits(
        "live-web",
        root.path("results"),
        clock.instant(),
        (item, now, rank) -> toHit(item, "live-web", now, rank));
  }

  static String topic(SourceQuery query) {
    return query.getWebQuery() != null && query.getPublishedAfter() == null ? "general" : "news";
  }

  static String queryText(SourceQuery query) {
    if (query.getWebQuery() != null) {
      return query.getWebQuery();
    }
    String text = HttpClients.queryText(query);
    return text + " artificial intelligence";
  }

  public static SourceHit toHit(JsonNode r, String sourceId, Instant now, int position) {
    String title = HttpClients.text(r, "title");
    ResourceType type =
        title != null && EVENT.matcher(title).find() ? ResourceType.EVENT : ResourceType.NEWS;
    return toHit(r, sourceId, now, position, type, Set.of());
  }

  public static SourceHit toHit(
      JsonNode r, String sourceId, Instant now, int position, ResourceType type, Set<String> tags) {
    String url = HttpClients.text(r, "url");
    String title = HttpClients.text(r, "title");
    CanonicalUrl canonical = CanonicalUrl.of(url);
    if (url == null || title == null || canonical == null || isTavilysOwnPage(canonical)) {
      return null;
    }
    String content = HttpClients.text(r, "content");
    if (!Classification.isAiRelated(title, content, Set.of())) {
      return null;
    }
    WebPageTitle page = WebPageTitle.of(title, canonical.getHost());
    String snippet = snippet(content != null ? content : page.getDescription());
    return new SourceHit(
        sourceId,
        canonical.getValue(),
        HttpClients.link(url, canonical),
        canonical,
        null,
        page.getTitle(),
        snippet,
        ValueOrigin.SOURCE_PROVIDED,
        type,
        Audience.BOTH,
        null,
        Set.of(),
        tags,
        null,
        null,
        Set.of(),
        null,
        DateFact.fromSource(date(HttpClients.text(r, "published_date")), sourceId, now),
        DateFact.unknown(),
        List.of(),
        now,
        position);
  }

  public static boolean isTavilysOwnPage(CanonicalUrl url) {
    String host = url.getHost();
    return host.equals("tavily.com") || host.endsWith(".tavily.com");
  }

  public static String snippet(String text) {
    return text == null ? null : text.length() > 400 ? text.substring(0, 397) + "…" : text;
  }

  static Instant date(String s) {
    if (s == null) {
      return null;
    }
    try {
      return Instant.parse(s);
    } catch (RuntimeException ignored) {
    }
    try {
      return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH))
          .toInstant();
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
