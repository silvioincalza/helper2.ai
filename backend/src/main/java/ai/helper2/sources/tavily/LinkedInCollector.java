package ai.helper2.sources.tavily;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
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

/**
 * Public LinkedIn pages about AI (ADR 0053): articles, posts, company and showcase pages, events.
 * LinkedIn has no search API and forbids scraping, so we never call LinkedIn: one Tavily search per
 * fixed query, restricted to linkedin.com with include_domains, on a weekly interval (one credit
 * per query and pass, search_depth basic). The URL path alone decides what a page is; personal
 * profiles, jobs and learning pages are dropped. Every kept page is a candidate for the admin
 * review list, never a hit, so nothing reaches the catalog without a person accepting it. Titles
 * carry no author name, and excerpts are short. A pass asks only for pages published since the
 * previous complete pass (start_date), or the last month on the first.
 *
 * <p>Built as the discovery collectors are (ADR 0051): every search counts its credits on the
 * global counter and the Scout's daily cap ({@link ScoutTavilyCredits}, feature {@value #FEATURE});
 * past the cap the collector skips. A search that fails alone is logged and skipped; when half or
 * more fail, the pass fails and its checkpoint stays. Docs:
 * https://docs.tavily.com/documentation/api-reference/endpoint/search
 */
public class LinkedInCollector implements Collector {

  private static final Logger log = LoggerFactory.getLogger(LinkedInCollector.class);

  static final String ID = "linkedin-scout";

  /** Circuit breaker and retry of this collector's calls (ADR 0033). */
  static final String POLICY = "collector-" + ID;

  /** The tag every accepted page gets, so search can filter LinkedIn pages. */
  static final String TAG = "linkedin";

  /** The feature tag of its Tavily credits, beside discovery's "scout-discovery". */
  static final String FEATURE = "scout-linkedin";

  static final int MAX_RESULTS_PER_QUERY = 10;

  /** One credit per search; the excerpt is all a candidate needs. */
  static final String SEARCH_DEPTH = "basic";

  /** Shorter than other web excerpts: a post's text often names people. */
  static final int MAX_SNIPPET = 300;

  private static final int MAX_POST_TITLE = 120;

  /** What a LinkedIn page is, from its path. */
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

  /** "Jane Doe on LinkedIn: text" and "Jane Doe's Post": the person goes, the text stays. */
  private static final Pattern ON_LINKEDIN =
      Pattern.compile("^.{1,100}?\\s+on LinkedIn:\\s*", Pattern.CASE_INSENSITIVE);

  private static final Pattern NAME_ONLY =
      Pattern.compile(
          "^.{1,100}?['’]s (Post|Activity|Article)$|^Post (by|from) .+$", Pattern.CASE_INSENSITIVE);

  /** " | LinkedIn", " - LinkedIn", " · LinkedIn" and what follows: the site, not the page. */
  private static final Pattern SITE_SUFFIX =
      Pattern.compile("\\s+[|\\-·—–]\\s+LinkedIn\\b.*$", Pattern.CASE_INSENSITIVE);

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final List<String> queries;
  private final ScoutTavilyCredits credits;
  private final SourceDescriptor descriptor;

  public LinkedInCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      List<String> queries,
      ScoutTavilyCredits credits) {
    this.clock = clock;
    this.credits = credits.forFeature(FEATURE);
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.queries = queries.stream().map(String::strip).filter(q -> !q.isEmpty()).toList();
    this.http = HttpClients.client(builder, settings);
    this.descriptor =
        new SourceDescriptor(
            ID,
            "LinkedIn via Tavily (sent to review)",
            "LinkedIn",
            true,
            SourceMode.INDEXED,
            URI.create("https://www.linkedin.com"),
            URI.create("https://tavily.com/terms"),
            "Public LinkedIn pages found via the Tavily Search API; each links to LinkedIn. An admin"
                + " reviews each before it is catalogued.",
            EnumSet.of(
                ResourceType.ARTICLE, ResourceType.NEWS, ResourceType.PRODUCT, ResourceType.EVENT),
            settings.timeout(),
            settings.cacheTtl(),
            settings.enabled() && settings.hasToken() && !this.queries.isEmpty(),
            60);
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
      return CollectedBatch.skipped(
          "Daily Tavily credit cap of the Scout reached ("
              + credits.dailyCap()
              + " credits); next try tomorrow");
    }
    int perQuery =
        Math.max(1, Math.min(MAX_RESULTS_PER_QUERY, maxItems / Math.max(1, queries.size())));
    Map<String, Candidate> found = searchAll(TavilyCollector.startDate(checkpoint), perQuery);
    return new CollectedBatch(
        List.of(), null, startedAt.toString(), false, List.copyOf(found.values()));
  }

  private String notDueReason(@Nullable String checkpoint, Instant now) {
    LocalDate next = interval.nextDueDay(checkpoint, now);
    return next == null ? "Not due yet (run interval)" : "Not due yet: next pass on " + next;
  }

  /**
   * Every query, merged by URL. A search that fails alone is skipped, so the paid ones are kept;
   * when half or more fail, the pass fails and its checkpoint stays (as discovery, ADR 0051).
   */
  private Map<String, Candidate> searchAll(@Nullable String since, int perQuery) {
    Map<String, Candidate> byUrl = new LinkedHashMap<>();
    RuntimeException lastFailure = null;
    int failed = 0;
    for (String query : queries) {
      try {
        JsonNode root = searchFor(requestBody(query, since, perQuery));
        credits.spent(root, SEARCH_DEPTH);
        for (JsonNode r : root.path("results")) {
          Candidate c = candidate(r);
          if (c != null) {
            byUrl.putIfAbsent(c.getUrl().getValue(), c);
          }
        }
      } catch (RuntimeException e) {
        log.warn("{}: a search failed: {}", ID, e.getClass().getSimpleName());
        lastFailure = e;
        failed++;
      }
    }
    if (lastFailure != null && failed * 2 >= queries.size()) {
      throw lastFailure;
    }
    return byUrl;
  }

  private JsonNode searchFor(Map<String, Object> body) {
    return resilience.call(
        POLICY,
        () ->
            HttpClients.getJson(
                () -> http.post().uri("/search").body(body).retrieve().body(String.class), clock));
  }

  /**
   * The search request: linkedin.com only, the query, only pages new since [since]. Asks for the
   * credits used; never for page text, images or an answer.
   */
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

  /**
   * One Tavily result as a candidate; null when it is not on LinkedIn, its path is not an article,
   * post, company page or event, or it has no title left once the author is removed.
   */
  static Candidate candidate(JsonNode r) {
    String raw = HttpClients.text(r, "url");
    // LinkedIn's query strings are tracking (trk, originalSubdomain, ...): the path is the page.
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

  /** The kind of a linkedin.com page from its path; null for any other page or host. */
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

  /**
   * The title without the author and without the site: "Jane Doe on LinkedIn: New model out" → "New
   * model out"; "Agents in prod | Jane Doe | 3 comments" → "Agents in prod". A post whose title
   * names only its author ("Jane Doe's Post") takes the start of its text. Null when nothing is
   * left.
   */
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
