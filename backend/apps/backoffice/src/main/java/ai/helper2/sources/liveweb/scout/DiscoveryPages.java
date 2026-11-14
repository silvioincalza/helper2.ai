package ai.helper2.sources.liveweb.scout;

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

public final class DiscoveryPages {
  private final Set<String> writingHosts;

  private final Set<String> directoryHosts;

  private final Set<String> publisherHosts;

  public DiscoveryPages(HostLists hosts) {
    this.writingHosts = Set.copyOf(hosts.getWriting());
    this.directoryHosts = Set.copyOf(hosts.getDirectory());
    this.publisherHosts = union(writingHosts, directoryHosts);
  }

  enum Shape {
    LIST,
    PRODUCT,
    ARTICLE
  }

  record Page(Shape shape, CanonicalUrl url, String title) {}

  private static final Pattern LIST_TITLE =
      Pattern.compile(
          "\\b(\\d+\\s+(best|top|free|open[- ]source|ai|great|must[- ]have)\\b|top\\s+\\d+"
              + "|best\\s+(free\\s+|open[- ]source\\s+)?(ai\\s+)?\\S+\\s+(tools|apps|platforms|software"
              + "|alternatives|assistants|generators|extensions|libraries|frameworks)\\b"
              + "|vs\\.?\\s|versus|alternatives\\b|compared|comparison|roundup|list of|directory)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern POST_SLUG = Pattern.compile("([a-z0-9]+-){3,}[a-z0-9]+");

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

  private static final Set<String> ARTICLE_PLACES = union(WRITING_PLACES, OTHER_PLACES);

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

  private static final Pattern LOCALE = Pattern.compile("[a-z]{2}(-[a-z]{2})?");

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

  boolean isWritingPage(CanonicalUrl url) {
    String host = url.getHost();
    List<String> path = pagePath(url);
    if (path.isEmpty() || hostIn(host, directoryHosts) || host.equals("github.com")) {
      return false;
    }
    List<String> lower = path.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
    if (lower.getLast().startsWith("@")
        || lower.stream().anyMatch(OTHER_PLACES::contains)
        || (ARTICLE_PLACES.contains(host.split("\\.")[0]) && !isWritingHost(host))) {
      return false;
    }
    boolean belowWritingPlace =
        lower.subList(0, lower.size() - 1).stream().anyMatch(WRITING_PLACES::contains);
    return belowWritingPlace || isWritingHost(host) || POST_SLUG.matcher(lower.getLast()).matches();
  }

  private boolean isWritingHost(String host) {
    return WRITING_PLACES.contains(host.split("\\.")[0]) || hostIn(host, writingHosts);
  }

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

  private static boolean namesSite(String text, CanonicalUrl site) {
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.isEmpty() || PAGE_WORDS.contains(lower)) {
      return false;
    }
    String name = lower.replaceAll("[^a-z0-9]", "");
    String hostName = site.getHost().split("\\.")[0].replaceAll("[^a-z0-9]", "");
    return !name.isEmpty() && (name.contains(hostName) || hostName.contains(name));
  }

  static boolean isListPage(String title, CanonicalUrl url) {
    return LIST_TITLE.matcher(title).find() && !segments(url).isEmpty();
  }

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
