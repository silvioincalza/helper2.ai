package ai.helper2.sources.tavily;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.WebPageTitle;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Tavily discovery (ADR 0051): one web search per category of the AI world (prompt libraries,
 * self-hosted apps, model hosting, ...), on a weekly interval. Each result is sorted by code, never
 * by a model:
 *
 * <ul>
 *   <li>a product's own site (its home page, or one level below it) or a GitHub repository is a
 *       candidate for the admin review list; it reaches the catalog only when an admin accepts it;
 *   <li>a list or comparison page ("10 tools for ...", "X vs Y", "alternatives to X") that is not a
 *       product's own home page or repository is indexed as an ARTICLE, with Tavily's excerpt;
 *   <li>anything else (a blog post, a docs page, a page not about AI, a low score) is dropped.
 * </ul>
 *
 * A result gets the tag of every category it belongs to: each category whose search found it, and
 * each category whose words are in its title or excerpt. A candidate's type is the most specific
 * type of the categories whose search found it. A search that fails alone is logged and skipped, so
 * the searches already paid for are kept; when half or more fail (an outage), the pass fails and
 * its checkpoint stays, so the next run searches the same pages again. A pass asks only for pages
 * published since the previous complete pass (start_date), or the last month on the first. Docs:
 * https://docs.tavily.com/documentation/api-reference/endpoint/search
 */
public class TavilyDiscoveryCollector implements Collector {

  private static final Logger log = LoggerFactory.getLogger(TavilyDiscoveryCollector.class);

  static final String ID = "tavily-discovery";

  /** Circuit breaker and retry of this collector's calls (ADR 0033). */
  static final String POLICY = "collector-" + ID;

  /**
   * "10 best ...", "top 5 ...", "best AI coding tools", "X vs Y", "alternatives to X": pages that
   * compare tools. Not "the best way to ..." or "an alternative to ...": product pages say that.
   */
  private static final Pattern LIST_TITLE =
      Pattern.compile(
          "\\b(\\d+\\s+(best|top|free|open[- ]source|ai|great|must[- ]have)\\b|top\\s+\\d+"
              + "|best\\s+(free\\s+|open[- ]source\\s+)?(ai\\s+)?\\S+\\s+(tools|apps|platforms|software"
              + "|alternatives|assistants|generators|extensions|libraries|frameworks)\\b"
              + "|vs\\.?\\s|versus|alternatives\\b|compared|comparison|roundup|list of|directory)",
          Pattern.CASE_INSENSITIVE);

  /** A tag: lower-case words joined by hyphens ("self-hosted", "model-hosting"). */
  private static final Pattern TAG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  /** A path segment that reads like a post's slug ("how-we-built-our-ai-stack"). */
  private static final Pattern POST_SLUG = Pattern.compile("([a-z0-9]+-){3,}[a-z0-9]+");

  /** Words after a title's last separator that name a page, not the site ("Acme | Pricing"). */
  private static final Set<String> PAGE_WORDS =
      Set.of(
          "home",
          "pricing",
          "features",
          "download",
          "downloads",
          "product",
          "products",
          "overview",
          "welcome",
          "platform",
          "official site",
          "official website");

  /** First path segments of github.com that are GitHub's own pages, not an owner. */
  private static final Set<String> GITHUB_PAGES =
      Set.of(
          "topics",
          "collections",
          "orgs",
          "marketplace",
          "sponsors",
          "features",
          "explore",
          "trending",
          "settings",
          "apps",
          "enterprise",
          "about",
          "pricing",
          "search",
          "login",
          "join",
          "customer-stories",
          "readme",
          "events",
          "security",
          "solutions",
          "resources");

  /**
   * Writing about a product, not the product: as the first path segment (example.com/blog) or the
   * first label of the host (blog.example.com).
   */
  private static final Set<String> ARTICLE_PLACES =
      Set.of(
          "blog",
          "news",
          "article",
          "articles",
          "post",
          "posts",
          "guide",
          "guides",
          "learn",
          "resources",
          "insights",
          "stories",
          "press",
          "docs",
          "doc",
          "documentation",
          "help",
          "support",
          "wiki",
          "tag",
          "tags",
          "category",
          "topics",
          "search",
          "careers",
          "jobs",
          "events",
          "about",
          "legal",
          "privacy",
          "terms",
          "login",
          "signup",
          "community",
          "forum",
          "status",
          "changelog");

  /** Hosts that publish about many products; a page there is never a product's own site. */
  private static final Set<String> PUBLISHER_HOSTS =
      Set.of(
          "medium.com",
          "dev.to",
          "substack.com",
          "forbes.com",
          "techcrunch.com",
          "theverge.com",
          "wired.com",
          "zdnet.com",
          "venturebeat.com",
          "producthunt.com",
          "huggingface.co",
          "wikipedia.org",
          "news.ycombinator.com",
          "g2.com",
          "capterra.com",
          "gartner.com",
          "hashnode.dev",
          "wordpress.com",
          "blogspot.com",
          "notion.site",
          "beehiiv.com",
          "theresanaiforthat.com",
          "futurepedia.io",
          "futuretools.io",
          "toolify.ai",
          "topai.tools",
          "aitools.fyi",
          "getapp.com",
          "softwareadvice.com",
          "trustradius.com",
          "trustpilot.com",
          "alternativeto.net",
          "saasworthy.com");

  /**
   * Pages of a site that stand for the site itself: a page one level below the home page with one
   * of these names becomes the home page. Any other one-level page (aws.amazon.com/bedrock) is the
   * product.
   */
  private static final Set<String> SITE_PAGES =
      Set.of(
          "pricing",
          "plans",
          "features",
          "product",
          "products",
          "download",
          "downloads",
          "platform",
          "enterprise",
          "integrations",
          "api",
          "app",
          "home",
          "overview");

  /** A first path segment that is only a language ("en", "de", "en-us"). */
  private static final Pattern LOCALE = Pattern.compile("[a-z]{2}(-[a-z]{2})?");

  /**
   * One category of the AI world: the type a product found by its search gets, its tag in the
   * catalog, the search query, and the words that put any result in it, whichever search found the
   * result.
   */
  public record Category(ResourceType type, String tag, String query, List<String> keywords) {

    public Category {
      keywords = List.copyOf(keywords);
    }

    /**
     * "TYPE|tag|query|word;word"; null when unreadable, when the tag is not lower-case words with
     * hyphens, or when the type is not one a product page can have. The words are optional.
     */
    public static @Nullable Category parse(String spec) {
      String[] parts = spec.split("\\|", -1);
      if (parts.length != 3 && parts.length != 4) {
        return null;
      }
      String tag = parts[1].strip();
      String query = parts[2].strip();
      ResourceType type = typeOf(parts[0].strip());
      if (type == null || !TAG.matcher(tag).matches() || query.isEmpty()) {
        return null;
      }
      List<String> keywords =
          parts.length == 3
              ? List.of()
              : Arrays.stream(parts[3].split(";"))
                  .map(k -> k.strip().toLowerCase(Locale.ROOT))
                  .filter(k -> !k.isEmpty())
                  .toList();
      return new Category(type, tag, query, keywords);
    }

    /**
     * True when [text] has one of the words at the start of a word ("fine-tun" in "fine-tuning").
     */
    boolean describes(String text) {
      String normal = normal(text);
      return keywords.stream()
          .anyMatch(k -> Pattern.compile("\\b" + Pattern.quote(normal(k))).matcher(normal).find());
    }

    /** Lower case, hyphens and underscores as spaces: "Text-to-Speech" reads "text to speech". */
    private static String normal(String text) {
      return text.toLowerCase(Locale.ROOT).replaceAll("[-_]+", " ");
    }

    private static @Nullable ResourceType typeOf(String name) {
      try {
        ResourceType type = ResourceType.valueOf(name.toUpperCase(Locale.ROOT));
        return PRODUCT_TYPES.contains(type) ? type : null;
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
  }

  /** Types a product's own site or repository can have; the reading and course types cannot. */
  static final Set<ResourceType> PRODUCT_TYPES =
      EnumSet.of(
          ResourceType.PRODUCT,
          ResourceType.AGENT,
          ResourceType.PROMPT,
          ResourceType.SKILL,
          ResourceType.MCP_SERVER,
          ResourceType.LIBRARY,
          ResourceType.MODEL,
          ResourceType.DATASET);

  /** What to send Tavily, besides the query: depth, size, score floor and domains to skip. */
  public record Search(
      String searchDepth, int maxResults, double minScore, List<String> excludeDomains) {
    public Search {
      excludeDomains = List.copyOf(excludeDomains);
    }
  }

  /** What a result is: a list page to index or a product site to review. */
  enum Shape {
    LIST,
    PRODUCT
  }

  /**
   * One result as the searches found it, merged across searches by URL: the categories whose search
   * found it, and the tags of every category it belongs to.
   */
  static final class Found {
    final Shape shape;
    final JsonNode result;
    final int rank;
    final CanonicalUrl url;
    final String title;
    final @Nullable String content;
    final List<Category> foundBy = new ArrayList<>();
    final Set<String> tags = new TreeSet<>();

    Found(
        Shape shape,
        JsonNode result,
        int rank,
        CanonicalUrl url,
        String title,
        @Nullable String content) {
      this.shape = shape;
      this.result = result;
      this.rank = rank;
      this.url = url;
      this.title = title;
      this.content = content;
    }

    /**
     * The most specific type of the categories whose search found it: an agent platform that is
     * also self-hosted is an AGENT.
     */
    ResourceType type() {
      return foundBy.stream()
          .map(Category::type)
          .min(Comparator.comparingInt(ResourceType::getSpecificity))
          .orElse(ResourceType.PRODUCT);
    }
  }

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final Search search;
  private final List<Category> categories;
  private final SourceDescriptor descriptor;

  public TavilyDiscoveryCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      Search search,
      List<String> categorySpecs) {
    this.clock = clock;
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.search = search;
    this.categories = categorySpecs.stream().map(Category::parse).filter(Objects::nonNull).toList();
    this.http = HttpClients.client(builder, settings);
    this.descriptor =
        new SourceDescriptor(
            ID,
            "Tavily discovery (lists indexed, products sent to review)",
            "Discovery",
            true,
            SourceMode.INDEXED,
            URI.create("https://tavily.com"),
            URI.create("https://tavily.com/terms"),
            "Web results via the Tavily Search API; each links to the original publisher. Product"
                + " pages are catalogued only after an admin accepts them.",
            resourceTypes(),
            settings.timeout(),
            settings.cacheTtl(),
            settings.enabled() && settings.hasToken() && !this.categories.isEmpty(),
            50);
  }

  /** List pages and every type a product candidate can have. */
  private static Set<ResourceType> resourceTypes() {
    Set<ResourceType> types = EnumSet.copyOf(PRODUCT_TYPES);
    types.add(ResourceType.ARTICLE);
    return types;
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
      return CollectedBatch.notDue();
    }
    Map<String, Found> found = searchAll(TavilyCollector.startDate(checkpoint));
    found.values().forEach(this::tagEveryCategory);
    return new CollectedBatch(
        found.values().stream()
            .filter(f -> f.shape == Shape.LIST)
            .map(f -> listHit(f, startedAt))
            .filter(Objects::nonNull)
            .toList(),
        null,
        startedAt.toString(),
        false,
        found.values().stream()
            .filter(f -> f.shape == Shape.PRODUCT)
            .map(this::candidate)
            .toList());
  }

  /**
   * Every category's search, merged by URL. A search that fails alone is skipped, so the paid ones
   * are kept; when half or more fail, the pass fails and its checkpoint stays.
   */
  private Map<String, Found> searchAll(@Nullable String since) {
    Map<String, Found> found = new LinkedHashMap<>();
    RuntimeException lastFailure = null;
    int failed = 0;
    for (Category category : categories) {
      List<Found> results;
      try {
        results = sort(searchFor(category, since));
      } catch (RuntimeException e) {
        log.warn("{}: search for {} failed: {}", ID, category.tag(), e.getClass().getSimpleName());
        lastFailure = e;
        failed++;
        continue;
      }
      for (Found f : results) {
        Found merged = found.computeIfAbsent(f.url.getValue(), k -> f);
        merged.foundBy.add(category);
        merged.tags.add(category.tag());
      }
    }
    // A circuit that opens mid-pass fails the rest fast: keep the checkpoint, so tomorrow's run
    // searches this week's pages again, rather than losing them.
    if (lastFailure != null && failed * 2 >= categories.size()) {
      throw lastFailure;
    }
    return found;
  }

  private JsonNode searchFor(Category category, @Nullable String since) {
    Map<String, Object> body = requestBody(category, since);
    return resilience.call(
        POLICY,
        () ->
            HttpClients.getJson(
                () -> http.post().uri("/search").body(body).retrieve().body(String.class), clock));
  }

  /** The search request: general web, the category's query, only pages new since [since]. */
  Map<String, Object> requestBody(Category category, @Nullable String since) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", category.query());
    body.put("topic", "general");
    body.put("search_depth", search.searchDepth());
    body.put("max_results", search.maxResults());
    body.put("include_answer", false);
    body.put("include_raw_content", false);
    if (!search.excludeDomains().isEmpty()) {
      body.put("exclude_domains", search.excludeDomains());
    }
    if (since != null) {
      body.put("start_date", since);
    } else {
      body.put("time_range", "month");
    }
    return body;
  }

  /** One search's product sites and list pages, in Tavily's order; drops the rest. */
  List<Found> sort(JsonNode root) {
    List<Found> kept = new ArrayList<>();
    int position = 0;
    for (JsonNode r : root.path("results")) {
      int rank = position++;
      if (r.path("score").asDouble(0) < search.minScore()) {
        continue;
      }
      String title = HttpClients.text(r, "title");
      CanonicalUrl url = CanonicalUrl.of(HttpClients.text(r, "url"));
      String content = HttpClients.text(r, "content");
      if (title == null || url == null || !Classification.isAiRelated(title, content, Set.of())) {
        continue;
      }
      Found f = shapeOf(r, rank, url, title, content);
      if (f != null) {
        kept.add(f);
      }
    }
    return kept;
  }

  /**
   * A repository or a home page is the product itself, whatever its title says ("an open-source
   * alternative to ChatGPT"). Any other page with a list-like title is a list; any other page one
   * level below a home page stands for its site.
   */
  private static @Nullable Found shapeOf(
      JsonNode r, int rank, CanonicalUrl url, String title, @Nullable String content) {
    CanonicalUrl site = productSite(url);
    if (site != null && (isRepository(url) || pagePath(url).isEmpty())) {
      return new Found(Shape.PRODUCT, r, rank, site, siteTitle(title, url, site), content);
    }
    if (isListPage(title, url)) {
      return new Found(Shape.LIST, r, rank, url, title, content);
    }
    return site == null
        ? null
        : new Found(Shape.PRODUCT, r, rank, site, siteTitle(title, url, site), content);
  }

  /** Adds the tag of every category whose words are in the result's title or excerpt. */
  private void tagEveryCategory(Found f) {
    String text = f.title + " " + (f.content == null ? "" : f.content);
    categories.stream().filter(c -> c.describes(text)).forEach(c -> f.tags.add(c.tag()));
  }

  private @Nullable SourceHit listHit(Found f, Instant now) {
    return TavilySource.toHit(f.result, ID, now, f.rank, ResourceType.ARTICLE, Set.copyOf(f.tags));
  }

  private Candidate candidate(Found f) {
    return new Candidate(
        f.url,
        f.title,
        TavilySource.snippet(f.content),
        String.join(", ", f.tags),
        f.type(),
        Set.copyOf(f.tags));
  }

  /**
   * The candidate's title: the page title for a home page or a repository. For a page below the
   * home page, the side of the title's last separator that names the site ("Pricing | AnythingLLM"
   * and "AnythingLLM - Pricing" both give "AnythingLLM"), or the host when neither side does.
   */
  static String siteTitle(String title, CanonicalUrl page, CanonicalUrl site) {
    if (page.equals(site) || !pagePath(site).isEmpty()) {
      return WebPageTitle.of(title, site.getHost()).getTitle();
    }
    String[] sides = title.strip().split("\\s[|·—–-]\\s");
    if (sides.length < 2) {
      return site.getHost();
    }
    String last = sides[sides.length - 1].strip();
    String first = sides[0].strip();
    if (namesSite(last, site)) {
      return last;
    }
    if (namesSite(first, site)) {
      return first;
    }
    return site.getHost();
  }

  /**
   * [text] is the site's name: not a page word, and the host's name is in it or it is in the host.
   */
  private static boolean namesSite(String text, CanonicalUrl site) {
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.isEmpty() || PAGE_WORDS.contains(lower)) {
      return false;
    }
    String name = lower.replaceAll("[^a-z0-9]", "");
    String hostName = site.getHost().split("\\.")[0].replaceAll("[^a-z0-9]", "");
    return !name.isEmpty() && (name.contains(hostName) || hostName.contains(name));
  }

  /** A page that compares or lists tools: a list-like title, below a home page. */
  static boolean isListPage(String title, CanonicalUrl url) {
    return LIST_TITLE.matcher(title).find() && !segments(url).isEmpty();
  }

  /**
   * The product's own page for [url], or null when the page is writing about a product (a blog path
   * or host, a post's slug) or on a publisher's host:
   *
   * <ul>
   *   <li>a GitHub repository, as it is;
   *   <li>a home page (a language segment such as /en does not count), as it is;
   *   <li>a site page one level below it (/pricing, /features), as the site's home page;
   *   <li>any other page one level below it (aws.amazon.com/bedrock), as it is: big vendors have
   *       one product per page.
   * </ul>
   */
  static @Nullable CanonicalUrl productSite(CanonicalUrl url) {
    String host = url.getHost();
    if (host.equals("github.com")) {
      return isRepository(url) ? url : null;
    }
    List<String> path = pagePath(url);
    if (isPublisher(host) || isArticleHost(host) || path.size() > 1) {
      return null;
    }
    if (path.isEmpty()) {
      return url;
    }
    String page = path.getFirst().toLowerCase(Locale.ROOT);
    if (isArticlePath(page)) {
      return null;
    }
    return SITE_PAGES.contains(page) ? CanonicalUrl.of("https://" + host + "/") : url;
  }

  private static boolean isRepository(CanonicalUrl url) {
    List<String> path = segments(url);
    return url.getHost().equals("github.com")
        && path.size() == 2
        && !GITHUB_PAGES.contains(path.getFirst().toLowerCase(Locale.ROOT));
  }

  /** The path without a leading language segment. */
  private static List<String> pagePath(CanonicalUrl url) {
    List<String> path = segments(url);
    boolean language =
        !path.isEmpty() && LOCALE.matcher(path.getFirst().toLowerCase(Locale.ROOT)).matches();
    return language ? path.subList(1, path.size()) : path;
  }

  private static boolean isArticlePath(String segment) {
    return ARTICLE_PLACES.contains(segment) || POST_SLUG.matcher(segment).matches();
  }

  private static boolean isArticleHost(String host) {
    return ARTICLE_PLACES.contains(host.split("\\.")[0]);
  }

  private static boolean isPublisher(String host) {
    return PUBLISHER_HOSTS.stream().anyMatch(p -> host.equals(p) || host.endsWith("." + p));
  }

  private static List<String> segments(CanonicalUrl url) {
    String path = URI.create(url.getValue()).getRawPath();
    if (path == null) {
      return List.of();
    }
    return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
  }
}
