package ai.helper2.sources.github.scout;

import ai.helper2.scout.PageScope;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.liveweb.scout.DiscoveryPages;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class ProductLinks {
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

  record Checked(@Nullable CanonicalUrl site, @Nullable Skip skip) {

    static Checked kept(CanonicalUrl site) {
      return new Checked(site, null);
    }

    static Checked skipped(Skip skip) {
      return new Checked(null, skip);
    }
  }

  private static final int MAX_LENGTH = CanonicalUrl.MAX_LENGTH;

  private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\s]");

  private static final Pattern NUMERIC_LABEL = Pattern.compile("0x[0-9a-f]*|[0-9]+");

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

  private static final List<String> GITHUB_HOSTS = List.of("github.com", "githubusercontent.com");

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

  private static final Pattern EMBEDDED_URL =
      Pattern.compile("(?i)https?:|https?%3a|%3a%2f%2f|//|%2f%2f");

  private final List<String> excludedDomains;

  ProductLinks(Collection<String> excludedDomains) {
    this.excludedDomains =
        excludedDomains.stream()
            .map(d -> d.strip().toLowerCase(Locale.ROOT))
            .filter(d -> !d.isEmpty())
            .distinct()
            .toList();
  }

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

  private static boolean numericAuthority(URI uri) {
    String authority = uri.getRawAuthority();
    if (authority == null || authority.contains("@")) {
      return false;
    }
    String host = stripTrailingDot(authority.replaceFirst(":\\d*$", "").toLowerCase(Locale.ROOT));
    return !host.isEmpty()
        && Arrays.stream(host.split("\\.")).allMatch(l -> NUMERIC_LABEL.matcher(l).matches());
  }

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
