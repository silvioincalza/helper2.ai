package ai.helper2.sources.tavily;

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
 *   <li>anything else (a blog post, a docs page, a publisher's page) is neither: it is dropped.
 * </ul>
 */
final class DiscoveryPages {

  private DiscoveryPages() {}

  /** What a result is: a list page to index or a product site to review. */
  enum Shape {
    LIST,
    PRODUCT
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
   * A repository or a home page is the product itself, whatever its title says ("an open-source
   * alternative to ChatGPT"). Any other page with a list-like title is a list; any other page one
   * level below a home page stands for its site. Null: neither.
   */
  static @Nullable Page classify(CanonicalUrl url, String title) {
    CanonicalUrl site = productSite(url);
    if (site != null && (isRepository(url) || pagePath(url).isEmpty())) {
      return new Page(Shape.PRODUCT, site, siteTitle(title, url, site));
    }
    if (isListPage(title, url)) {
      return new Page(Shape.LIST, url, title);
    }
    return site == null ? null : new Page(Shape.PRODUCT, site, siteTitle(title, url, site));
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
