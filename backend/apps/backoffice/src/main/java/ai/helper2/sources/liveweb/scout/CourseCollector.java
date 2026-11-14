package ai.helper2.sources.liveweb.scout;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CollectorIds;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.WebPageTitle;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.TavilySource;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class CourseCollector implements Collector {

  static final String ID = CollectorIds.COURSES;

  static final String POLICY = "collector-" + ID;

  static final String FEATURE = "scout-courses";
  private static final String SEARCH_DEPTH = "basic";
  private static final int MAX_RESULTS_PER_PLATFORM = 10;
  private static final Pattern COURSE_WORDS =
      Pattern.compile(
          "\\b(courses?|certifications?|certificates?|certified|specializations?|training|bootcamp|exam|class|learning path|"
              + "nanodegree|workshop|live event|tutorials?|lessons?)\\b",
          Pattern.CASE_INSENSITIVE);

  record Platform(String host, String pathPrefix, String name) {
    static Platform parse(String spec) {
      int eq = spec.indexOf('=');
      String where = (eq < 0 ? spec : spec.substring(0, eq)).strip().toLowerCase(Locale.ROOT);
      String name = eq < 0 ? null : spec.substring(eq + 1).strip();
      if (where.isEmpty() || where.contains("://")) {
        return null;
      }
      int slash = where.indexOf('/');
      String host = slash < 0 ? where : where.substring(0, slash);
      String path = slash < 0 ? "" : where.substring(slash).replaceAll("/+$", "");
      return host.contains(".")
          ? new Platform(
              host.replaceFirst("^www\\.", ""), path, name == null || name.isEmpty() ? host : name)
          : null;
    }

    String where() {
      return host + pathPrefix;
    }

    boolean covers(CanonicalUrl url) {
      URI u = URI.create(url.getValue());
      String h = u.getHost();
      boolean onHost = h.equals(host) || h.endsWith("." + host);
      String path = u.getRawPath() == null ? "" : u.getRawPath();
      return onHost
          && (pathPrefix.isEmpty() || path.equals(pathPrefix) || path.startsWith(pathPrefix + "/"));
    }
  }

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final String query;
  private final List<Platform> platforms;
  private final SourceDescriptor descriptor;
  private final ScoutLiveWebCredits credits;

  public CourseCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      String query,
      List<String> platformSpecs,
      ScoutLiveWebCredits credits) {
    this.clock = clock;
    this.credits = credits.forFeature(FEATURE);
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.query = query == null ? "" : query.strip();
    this.platforms =
        platformSpecs.stream().map(Platform::parse).filter(java.util.Objects::nonNull).toList();
    this.http = HttpClients.client(builder, settings);
    this.descriptor = CollectorDescriptors.courses(settings, this.query, this.platforms);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public CollectedBatch collect(String checkpoint, String resumeCursor, int maxItems) {
    Instant startedAt = clock.instant();
    if (!interval.allowsPass(checkpoint, resumeCursor, startedAt)) {
      return CollectedBatch.notDue();
    }
    if (credits.capReached()) {
      return CollectedBatch.deferred(
          "Daily live web credit cap of the Scout reached ("
              + credits.dailyCap()
              + " credits); next try tomorrow");
    }
    Map<String, Candidate> byUrl = new LinkedHashMap<>();
    int perPlatform =
        Math.max(1, Math.min(MAX_RESULTS_PER_PLATFORM, maxItems / Math.max(1, platforms.size())));
    String since = LiveWebNewsCollector.startDate(checkpoint);
    Set<String> done = QueryProgress.done(resumeCursor);
    for (Platform p : platforms) {
      if (done.contains(p.where())) {
        continue;
      }
      if (credits.capReached()) {
        break;
      }
      JsonNode root = search(requestBody(p, since, perPlatform));
      for (JsonNode r : root.path("results")) {
        Candidate c = candidate(r, p);
        if (c != null) {
          byUrl.putIfAbsent(c.getUrl().getValue(), c);
        }
      }
      done.add(p.where());
    }
    boolean complete = platforms.stream().map(Platform::where).allMatch(done::contains);
    return new CollectedBatch(
        List.of(),
        complete ? null : QueryProgress.cursor(done),
        complete ? startedAt.toString() : null,
        false,
        List.copyOf(byUrl.values()));
  }

  private Map<String, Object> requestBody(Platform p, String since, int maxResults) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("topic", "general");
    body.put("search_depth", SEARCH_DEPTH);
    body.put("max_results", maxResults);
    body.put("include_domains", List.of(p.host()));
    if (since != null) {
      body.put("start_date", since);
    } else {
      body.put("time_range", "month");
    }
    return body;
  }

  private JsonNode search(Map<String, Object> body) {
    return resilience.call(
        POLICY,
        () ->
            credits.countedAttempt(
                SEARCH_DEPTH,
                () ->
                    HttpClients.getJson(
                        () -> http.post().uri("/search").body(body).retrieve().body(String.class),
                        clock)));
  }

  static Candidate candidate(JsonNode r, Platform p) {
    String title = HttpClients.text(r, "title");
    CanonicalUrl url = CanonicalUrl.of(HttpClients.text(r, "url"));
    if (title == null || url == null || !p.covers(url)) {
      return null;
    }
    String content = HttpClients.text(r, "content");
    if (!Classification.isAiRelated(title, content, Set.of())) {
      return null;
    }
    String text =
        title
            + " "
            + (content == null ? "" : content)
            + " "
            + url.getValue().replace('-', ' ').replace('/', ' ');
    if (!COURSE_WORDS.matcher(text).find()) {
      return null;
    }
    return new Candidate(
        url,
        WebPageTitle.of(title, url.getHost()).getTitle(),
        TavilySource.snippet(content),
        p.name(),
        ResourceType.COURSE);
  }
}
