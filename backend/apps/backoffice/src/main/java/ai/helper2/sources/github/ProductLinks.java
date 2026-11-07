package ai.helper2.sources.github;

import ai.helper2.scout.PageScope;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.tavily.DiscoveryPages;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which links of an awesome list point at a product's own site (ADR 0058), by code only. A link is
 * kept when it is a plain https address of a public name and the product's home page or its
 * one-level page ({@link DiscoveryPages#productSite}, the rules discovery uses). Everything else is
 * skipped with a reason: another scheme, an IP address, user info, a port, a private or reserved
 * name, a punycode name (a look-alike can hide in it), a link that redirects, GitHub's own pages,
 * an excluded domain (directories, review sites, social and video sites), or a page that writes
 * about a product (docs, blogs, articles). The kept URL has no query and no fragment.
 */
final class ProductLinks {

  /** Why a link is not kept; its {@link #tag} is the metric tag. */
  enum Skip {
    MALFORMED,
    NOT_HTTPS,
    USER_INFO,
    PORT,
    IP_ADDRESS,
    PRIVATE_HOST,
    PUNYCODE,
    GITHUB,
    EXCLUDED_DOMAIN,
    REDIRECT,
    NOT_A_PRODUCT_PAGE;

    String tag() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  /** A kept link's product site, or the reason it was skipped. Exactly one is set. */
  record Checked(@Nullable CanonicalUrl site, @Nullable Skip skip) {

    static Checked kept(CanonicalUrl site) {
      return new Checked(site, null);
    }

    static Checked skipped(Skip skip) {
      return new Checked(null, skip);
    }
  }

  private static final int MAX_LENGTH = 2_000;

  /** Control, format (bidi, zero-width) and space characters: never in a link we keep. */
  private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\s]");

  /** A host label that is a number in any notation an address parser accepts. */
  private static final Pattern NUMERIC_LABEL = Pattern.compile("0x[0-9a-f]*|[0-9]+");

  /** Top-level names that never reach the public internet (RFC 6761, 6762, 8375 and common use). */
  private static final Set<String> PRIVATE_SUFFIXES =
      Set.of(
          "localhost",
          "localdomain",
          "local",
          "internal",
          "intranet",
          "private",
          "lan",
          "home",
          "corp",
          "test",
          "example",
          "invalid",
          "onion",
          "arpa");

  /** GitHub's own hosts: a repository there is an entry, any other page is not a product. */
  private static final List<String> GITHUB_HOSTS = List.of("github.com", "githubusercontent.com");

  /** Link shorteners: the address does not say where it leads. */
  private static final List<String> SHORTENERS =
      List.of(
          "bit.ly",
          "t.co",
          "tinyurl.com",
          "goo.gl",
          "ow.ly",
          "buff.ly",
          "lnkd.in",
          "is.gd",
          "rebrand.ly",
          "cutt.ly",
          "rb.gy",
          "t.ly",
          "shorturl.at",
          "tiny.cc");

  /** First path segments of redirect pages ("/out?to=...", "/go/123"). */
  private static final Set<String> REDIRECT_SEGMENTS =
      Set.of(
          "redirect",
          "redir",
          "out",
          "outbound",
          "away",
          "go",
          "link",
          "url",
          "click",
          "track",
          "exit",
          "external",
          "r");

  /** A URL inside the path or the query, written plain or percent-encoded. */
  private static final Pattern EMBEDDED_URL =
      Pattern.compile("(?i)https?:|https?%3a|%3a%2f%2f|//|%2f%2f");

  private final List<String> excludedDomains;

  /**
   * @param excludedDomains domains whose pages are never a product's own site, subdomains included
   */
  ProductLinks(Collection<String> excludedDomains) {
    this.excludedDomains =
        excludedDomains.stream()
            .map(d -> d.strip().toLowerCase(Locale.ROOT))
            .filter(d -> !d.isEmpty())
            .distinct()
            .toList();
  }

  /** The product site [raw] points at, or why it is skipped. */
  Checked check(@Nullable String raw, DiscoveryPages pages) {
    URI uri = parse(raw);
    if (uri == null) {
      return Checked.skipped(Skip.MALFORMED);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      return Checked.skipped(Skip.NOT_HTTPS);
    }
    if (uri.getHost() == null) {
      return Checked.skipped(numericAuthority(uri) ? Skip.IP_ADDRESS : Skip.MALFORMED);
    }
    Skip plain = plainProblem(uri);
    if (plain != null) {
      return Checked.skipped(plain);
    }
    String host = stripTrailingDot(uri.getHost().toLowerCase(Locale.ROOT));
    Skip hostProblem = hostProblem(host);
    if (hostProblem != null) {
      return Checked.skipped(hostProblem);
    }
    if (isRedirect(uri)) {
      return Checked.skipped(Skip.REDIRECT);
    }
    CanonicalUrl page = CanonicalUrl.of("https://" + host + nullToEmpty(uri.getRawPath()));
    if (page == null) {
      return Checked.skipped(Skip.MALFORMED);
    }
    CanonicalUrl site = pages.productSite(page);
    return site == null ? Checked.skipped(Skip.NOT_A_PRODUCT_PAGE) : Checked.kept(site);
  }

  private static @Nullable URI parse(@Nullable String raw) {
    if (raw == null
        || raw.isEmpty()
        || raw.length() > MAX_LENGTH
        || INVISIBLE.matcher(raw).find()) {
      return null;
    }
    try {
      return new URI(raw);
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /**
   * Java's URI reads "127.1" or "0x7f.0.0.1" as no host at all (a label may not start with a digit
   * at the end); an address parser would read an IP address. Either way it is skipped, with the
   * reason that says why.
   */
  private static boolean numericAuthority(URI uri) {
    String authority = uri.getRawAuthority();
    if (authority == null || authority.contains("@")) {
      return false;
    }
    String host = stripTrailingDot(authority.replaceFirst(":\\d*$", "").toLowerCase(Locale.ROOT));
    return !host.isEmpty()
        && Arrays.stream(host.split("\\.")).allMatch(l -> NUMERIC_LABEL.matcher(l).matches());
  }

  /** The scout's rules for any page it may read (ADR 0056): a name, no user info, no port. */
  private static @Nullable Skip plainProblem(URI uri) {
    String problem = PageScope.plainUrlProblem(uri);
    if (problem == null) {
      return null;
    }
    return switch (problem) {
      case "user info" -> Skip.USER_INFO;
      case "port" -> Skip.PORT;
      case "ip address" -> Skip.IP_ADDRESS;
      case "no domain" -> Skip.PRIVATE_HOST;
      default -> Skip.MALFORMED;
    };
  }

  private @Nullable Skip hostProblem(String host) {
    String[] labels = host.split("\\.");
    if (Arrays.stream(labels).allMatch(l -> NUMERIC_LABEL.matcher(l).matches())) {
      return Skip.IP_ADDRESS;
    }
    if (PRIVATE_SUFFIXES.contains(labels[labels.length - 1])) {
      return Skip.PRIVATE_HOST;
    }
    if (Arrays.stream(labels).anyMatch(l -> l.startsWith("xn--"))) {
      return Skip.PUNYCODE;
    }
    if (under(host, GITHUB_HOSTS)) {
      return Skip.GITHUB;
    }
    if (under(host, SHORTENERS)) {
      return Skip.REDIRECT;
    }
    if (under(host, excludedDomains)) {
      return Skip.EXCLUDED_DOMAIN;
    }
    return null;
  }

  private static boolean isRedirect(URI uri) {
    String path = nullToEmpty(uri.getRawPath());
    String query = nullToEmpty(uri.getRawQuery());
    if (EMBEDDED_URL.matcher(path).find() || EMBEDDED_URL.matcher(query).find()) {
      return true;
    }
    String first = Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).findFirst().orElse("");
    return REDIRECT_SEGMENTS.contains(first.toLowerCase(Locale.ROOT));
  }

  private static boolean under(String host, List<String> domains) {
    for (String d : domains) {
      if (host.equals(d) || host.endsWith("." + d)) {
        return true;
      }
    }
    return false;
  }

  private static String stripTrailingDot(String host) {
    return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
  }

  private static String nullToEmpty(@Nullable String s) {
    return s == null ? "" : s;
  }
}
