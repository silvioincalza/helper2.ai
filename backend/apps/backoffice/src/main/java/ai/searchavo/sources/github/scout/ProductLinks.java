package ai.searchavo.sources.github.scout;

import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.WebAddresses;
import ai.searchavo.sources.liveweb.scout.DiscoveryPages;
import java.net.URI;
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
    SHADOW_LIBRARY,
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
  private final ShadowLibraries shadowLibraries;

  ProductLinks(Collection<String> excludedDomains, ShadowLibraries shadowLibraries) {
    this.shadowLibraries = shadowLibraries;
    this.excludedDomains =
        excludedDomains.stream()
            .map(d -> d.strip().toLowerCase(Locale.ROOT))
            .filter(d -> !d.isEmpty())
            .distinct()
            .toList();
  }

  Checked check(@Nullable String raw, DiscoveryPages pages) {
    Checked page = page(raw);
    return page.site() == null ? page : product(page.site(), pages);
  }

  Checked product(CanonicalUrl page, DiscoveryPages pages) {
    if (isExcluded(page)) {
      return Checked.skipped(Skip.EXCLUDED_DOMAIN);
    }
    CanonicalUrl site = pages.productSite(page);
    return site == null ? Checked.skipped(Skip.NOT_A_PRODUCT_PAGE) : Checked.kept(site);
  }

  /** A page on one of the excluded domains: never a product, wherever a link to it is found. */
  boolean isExcluded(CanonicalUrl page) {
    return under(page.getHost(), excludedDomains);
  }

  Checked page(@Nullable String raw) {
    if (!(WebAddresses.check(raw) instanceof WebAddresses.Checked.Public address)) {
      return Checked.skipped(skipOf(WebAddresses.check(raw)));
    }
    URI uri = address.getUri();
    String host = address.getHost();
    Skip hostProblem = hostProblem(host);
    if (hostProblem != null) {
      return Checked.skipped(hostProblem);
    }
    if (isRedirect(uri)) {
      return Checked.skipped(Skip.REDIRECT);
    }
    CanonicalUrl page = CanonicalUrl.of("https://" + host + nullToEmpty(uri.getRawPath()));
    return page == null ? Checked.skipped(Skip.MALFORMED) : Checked.kept(page);
  }

  private static Skip skipOf(WebAddresses.Checked refused) {
    return switch (((WebAddresses.Checked.Refused) refused).getRefusal()) {
      case MALFORMED, NOT_A_HOST_NAME -> Skip.MALFORMED;
      case NOT_HTTPS -> Skip.NOT_HTTPS;
      case USER_INFO -> Skip.USER_INFO;
      case PORT -> Skip.PORT;
      case IP_ADDRESS -> Skip.IP_ADDRESS;
      case PRIVATE_HOST -> Skip.PRIVATE_HOST;
      case PUNYCODE -> Skip.PUNYCODE;
    };
  }

  private @Nullable Skip hostProblem(String host) {
    if (under(host, GITHUB_HOSTS)) {
      return Skip.GITHUB;
    }
    if (under(host, SHORTENERS)) {
      return Skip.REDIRECT;
    }
    if (shadowLibraries.isShadowLibrary(host)) {
      return Skip.SHADOW_LIBRARY;
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

  private static String nullToEmpty(@Nullable String s) {
    return s == null ? "" : s;
  }
}
