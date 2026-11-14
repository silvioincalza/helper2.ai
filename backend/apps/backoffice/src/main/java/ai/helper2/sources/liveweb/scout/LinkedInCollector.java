package ai.helper2.sources.liveweb.scout;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class LinkedInCollector implements Collector {

  private static final Logger log = LoggerFactory.getLogger(LinkedInCollector.class);

  static final String ID = CollectorDescriptors.LINKEDIN;

  static final String POLICY = "collector-" + ID;

  static final String TAG = "linkedin";

  static final String FEATURE = "scout-linkedin";

  static final int MAX_RESULTS_PER_QUERY = 10;

  static final String SEARCH_DEPTH = "basic";

  static final int MAX_SNIPPET = 300;

  private static final int MAX_POST_TITLE = 120;

  enum Kind {
    ARTICLE(ResourceType.ARTICLE, "LinkedIn article"),
    POST(ResourceType.NEWS, "LinkedIn post"),
    COMPANY(ResourceType.PRODUCT, "LinkedIn company page"),
    EVENT(ResourceType.EVENT, "LinkedIn event");

    final ResourceType type;
    final String label;

    Kind(ResourceType type, String label) {
      this.type = type;
      this.label = label;
    }
  }

  private static final Pattern ARTICLE = Pattern.compile("^/pulse/[^/]+/?$");
  private static final Pattern POST =
      Pattern.compile("^/(posts/[^/]+|feed/update/[^/]+|company/[^/]+/posts/[^/]+)/?$");
  private static final Pattern COMPANY = Pattern.compile("^/(company|showcase)/[^/]+(/about)?/?$");
  private static final Pattern EVENT = Pattern.compile("^/events/[^/]+/?$");

  private static final Pattern ON_LINKEDIN =
      Pattern.compile("^.{1,100}?\\s+on LinkedIn:\\s*", Pattern.CASE_INSENSITIVE);

  private static final Pattern NAME_ONLY =
      Pattern.compile(
          "^.{1,100}?['’]s (Post|Activity|Article)$|^Post (by|from) .+$", Pattern.CASE_INSENSITIVE);

  private static final Pattern SITE_SUFFIX =
      Pattern.compile("\\s+[|\\-·—–]\\s+LinkedIn\\b.*$", Pattern.CASE_INSENSITIVE);

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final List<String> queries;
  private final ScoutLiveWebCredits credits;
  private final SourceDescriptor descriptor;

  public LinkedInCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      List<String> queries,
      ScoutLiveWebCredits credits) {
    this.clock = clock;
    this.credits = credits.forFeature(FEATURE);
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.queries = queries.stream().map(String::strip).filter(q -> !q.isEmpty()).toList();
    this.http = HttpClients.client(builder, settings);
    this.descriptor = CollectorDescriptors.linkedIn(settings, this.queries);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public CollectedBatch collect(
      @Nullable String checkpoint, @Nullable String resumeCursor, int maxItems) {
    Instant startedAt = clock.instant();
    if (!interval.allowsPass(checkpoint, resumeCursor, startedAt)) {
      return CollectedBatch.skipped(notDueReason(checkpoint, startedAt));
    }
    if (credits.capReached()) {
      return CollectedBatch.deferred(
          "Daily live web credit cap of the Scout reached ("
              + credits.dailyCap()
              + " credits); next try tomorrow");
    }
    int perQuery =
        Math.max(1, Math.min(MAX_RESULTS_PER_QUERY, maxItems / Math.max(1, queries.size())));
    Searched searched =
        searchAll(
            LiveWebNewsCollector.startDate(checkpoint), perQuery, QueryProgress.done(resumeCursor));
    return new CollectedBatch(
        List.of(),
        searched.complete() ? null : QueryProgress.cursor(searched.done()),
        searched.complete() ? startedAt.toString() : null,
        false,
        List.copyOf(searched.found().values()));
  }

  private String notDueReason(@Nullable String checkpoint, Instant now) {
    LocalDate next = interval.nextDueDay(checkpoint, now);
    return next == null ? "Not due yet (run interval)" : "Not due yet: next pass on " + next;
  }

  private record Searched(Map<String, Candidate> found, Set<String> done, boolean complete) {}

  private Searched searchAll(@Nullable String since, int perQuery, Set<String> done) {
    Map<String, Candidate> byUrl = new LinkedHashMap<>();
    RuntimeException lastFailure = null;
    int failed = 0;
    for (String query : queries) {
      if (done.contains(query)) {
        continue;
      }
      if (credits.capReached()) {
        log.info("{}: credit cap reached, the pass stays open", ID);
        break;
      }
      try {
        JsonNode root = searchFor(requestBody(query, since, perQuery));
        for (JsonNode r : root.path("results")) {
          Candidate c = candidate(r);
          if (c != null) {
            byUrl.putIfAbsent(c.getUrl().getValue(), c);
          }
        }
        done.add(query);
      } catch (RuntimeException e) {
        log.warn("{}: a search failed: {}", ID, e.getClass().getSimpleName());
        lastFailure = e;
        failed++;
      }
    }
    if (lastFailure != null && failed * 2 >= queries.size()) {
      throw lastFailure;
    }
    return new Searched(byUrl, done, done.containsAll(queries));
  }

  private JsonNode searchFor(Map<String, Object> body) {
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

  static Map<String, Object> requestBody(String query, @Nullable String since, int maxResults) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("topic", "general");
    body.put("search_depth", SEARCH_DEPTH);
    body.put("max_results", maxResults);
    body.put("include_domains", List.of("linkedin.com"));
    body.put("include_answer", false);
    body.put("include_raw_content", false);
    body.put("include_images", false);
    body.put("include_usage", true);
    if (since != null) {
      body.put("start_date", since);
    } else {
      body.put("time_range", "month");
    }
    return body;
  }

  static Candidate candidate(JsonNode r) {
    String raw = HttpClients.text(r, "url");
    CanonicalUrl url = raw == null ? null : CanonicalUrl.of(raw.replaceFirst("[?#].*$", ""));
    Kind kind = url == null ? null : kind(url);
    if (kind == null) {
      return null;
    }
    String content = HttpClients.text(r, "content");
    String title = title(kind, HttpClients.text(r, "title"), content);
    if (title == null) {
      return null;
    }
    return new Candidate(url, title, snippet(content), kind.label, kind.type, Set.of(TAG));
  }

  static Kind kind(CanonicalUrl url) {
    URI u = URI.create(url.getValue());
    String host = u.getHost();
    if (host == null || !(host.equals("linkedin.com") || host.endsWith(".linkedin.com"))) {
      return null;
    }
    String path = u.getRawPath() == null ? "" : u.getRawPath();
    if (ARTICLE.matcher(path).matches()) {
      return Kind.ARTICLE;
    }
    if (POST.matcher(path).matches()) {
      return Kind.POST;
    }
    if (COMPANY.matcher(path).matches()) {
      return Kind.COMPANY;
    }
    if (EVENT.matcher(path).matches()) {
      return Kind.EVENT;
    }
    return null;
  }

  static String title(Kind kind, String raw, String content) {
    String t = clean(raw);
    if (t != null) {
      Matcher on = ON_LINKEDIN.matcher(t);
      if (on.find()) {
        t = t.substring(on.end());
      }
      t = SITE_SUFFIX.matcher(t).replaceFirst("");
      int pipe = t.indexOf(" | ");
      if (pipe > 0) {
        t = t.substring(0, pipe);
      }
      t = clean(t);
    }
    if (kind == Kind.POST && (t == null || NAME_ONLY.matcher(t).matches())) {
      t = clean(content);
    } else if (t != null && NAME_ONLY.matcher(t).matches()) {
      t = null;
    }
    if (t == null) {
      return null;
    }
    return t.length() > MAX_POST_TITLE ? cutAtWord(t, MAX_POST_TITLE) : t;
  }

  static String snippet(String content) {
    String s = clean(content);
    return s == null ? null : s.length() > MAX_SNIPPET ? cutAtWord(s, MAX_SNIPPET) : s;
  }

  private static String clean(String s) {
    if (s == null) {
      return null;
    }
    String t = s.replaceAll("\\s+", " ").strip();
    return t.isEmpty() ? null : t;
  }

  private static String cutAtWord(String s, int max) {
    int cut = s.lastIndexOf(' ', max - 1);
    return s.substring(0, cut > max / 2 ? cut : max - 1).strip() + "…";
  }
}
