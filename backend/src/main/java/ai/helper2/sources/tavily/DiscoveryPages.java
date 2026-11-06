package ai.helper2.sources.tavily;

import ai.helper2.scout.HostLists;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.WebPageTitle;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a discovery result is, by its URL and title only (ADR 0051), never by a model:
 *
 * <ul>
 *   <li>a product's own site (its home page, or one level below it) or a GitHub repository is a
 *       PRODUCT page, a candidate for the admin review list;
 *   <li>a list or comparison page ("10 tools for ...", "X vs Y", "alternatives to X") that is not a
 *       product's own home page or repository is a LIST page, indexed as an ARTICLE;
 *   <li>a post that writes about products (a blog or news post, a publisher's article) is an
 *       ARTICLE page, a candidate for the admin review list too (ADR 0062): its own link is kept as
 *       an article, and its enrichment names what it is about;
 *   <li>anything else (a docs page, a directory entry, a careers page) is none: it is dropped.
 * </ul>
 *
 * <p>The awesome-list collector uses {@link #productSite} too (ADR 0058), so a link in a list is
 * kept by the same rules as a search result.
 */
public final class DiscoveryPages {

  /** Hosts that publish articles about many products: a post there is an ARTICLE (ADR 0062). */
  private final Set<String> writingHosts;

  /** Directories, reviews and wikis: they list products, and a page there is never kept. */
  private final Set<String> directoryHosts;

  /** Hosts that publish about many products; a page there is never a product's own site. */
  private final Set<String> publisherHosts;

  /**
   * The rules with the host lists of one run (ADR 0063): an admin keeps them in the back office,
   * and each run reads them once.
   */
  public DiscoveryPages(HostLists hosts) {
    this.writingHosts = Set.copyOf(hosts.getWriting());
    this.directoryHosts = Set.copyOf(hosts.getDirectory());
    this.publisherHosts = union(writingHosts, directoryHosts);
  }

  /** What a result is: a list page to index, or a product site or an article to review. */
  enum Shape {
    LIST,
    PRODUCT,
    ARTICLE
  }

  /** A kept result: its shape, the URL it is kept under (a product's site) and its title. */
  record Page(Shape shape, CanonicalUrl url, String title) {}

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
   * Places where a site writes about products (example.com/blog/..., news.example.com): a post
   * there is an ARTICLE (ADR 0062).
   */
  private static final Set<String> WRITING_PLACES =
      Set.of(
          "blog",
          "news",
          "article",
          "articles",
          "post",
          "posts",
          "insights",
          "stories",
          "press",
          "guide",
          "guides",
          "learn");

  /** Places of a site that are neither the product nor writing about it. */
  private static final Set<String> OTHER_PLACES =
      Set.of(
          "resources",
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

  /**
   * Not a product's own page: as the first path segment (example.com/blog) or the first label of
   * the host (blog.example.com).
   */
  private static final Set<String> ARTICLE_PLACES = union(WRITING_PLACES, OTHER_PLACES);

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
   * A repository or a home page is the product itself, whatever its title says ("an open-source
   * alternative to ChatGPT"). Any other page with a list-like title is a list; any other page one
   * level below a home page stands for its site. Null: neither.
   */
  @Nullable Page classify(CanonicalUrl url, String title) {
    CanonicalUrl site = productSite(url);
    if (site != null && (isRepository(url) || pagePath(url).isEmpty())) {
      return new Page(Shape.PRODUCT, site, siteTitle(title, url, site));
    }
    if (isListPage(title, url)) {
      return new Page(Shape.LIST, url, title);
    }
    if (site != null) {
      return new Page(Shape.PRODUCT, site, siteTitle(title, url, site));
    }
    return isWritingPage(url) ? new Page(Shape.ARTICLE, url, title.strip()) : null;
  }

  /**
   * A post that writes about products (ADR 0062): a page below a writing place (example.com/blog/x,
   * news.example.com/x), on a host that publishes articles, or with a post's slug. Not the index of
   * a blog, not a directory entry, nothing in a docs, careers or legal place.
   */
  boolean isWritingPage(CanonicalUrl url) {
    String host = url.getHost();
    List<String> path = pagePath(url);
    if (path.isEmpty() || hostIn(host, directoryHosts) || host.equals("github.com")) {
      return false;
    }
    List<String> lower = path.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
    // medium.com/@someone is an author's page, not a post.
    if (lower.getLast().startsWith("@")
        || lower.stream().anyMatch(OTHER_PLACES::contains)
        || (ARTICLE_PLACES.contains(host.split("\\.")[0]) && !isWritingHost(host))) {
      return false;
    }
    boolean belowWritingPlace =
        lower.subList(0, lower.size() - 1).stream().anyMatch(WRITING_PLACES::contains);
    return belowWritingPlace || isWritingHost(host) || POST_SLUG.matcher(lower.getLast()).matches();
  }

  /** blog.example.com, news.example.com, or a host that publishes articles about many products. */
  private boolean isWritingHost(String host) {
    return WRITING_PLACES.contains(host.split("\\.")[0]) || hostIn(host, writingHosts);
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
  public @Nullable CanonicalUrl productSite(CanonicalUrl url) {
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

  private boolean isPublisher(String host) {
    return hostIn(host, publisherHosts);
  }

  private static boolean hostIn(String host, Set<String> hosts) {
    return hosts.stream().anyMatch(p -> host.equals(p) || host.endsWith("." + p));
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> all = new java.util.HashSet<>(a);
    all.addAll(b);
    return Set.copyOf(all);
  }

  private static List<String> segments(CanonicalUrl url) {
    String path = URI.create(url.getValue()).getRawPath();
    if (path == null) {
      return List.of();
    }
    return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
  }
}
