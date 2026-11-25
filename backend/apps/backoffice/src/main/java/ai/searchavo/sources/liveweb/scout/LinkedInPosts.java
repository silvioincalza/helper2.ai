package ai.searchavo.sources.liveweb.scout;

import ai.searchavo.scout.PageScope;
import ai.searchavo.shared.CanonicalUrl;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LinkedInPosts {

  private static final Pattern LINK = Pattern.compile("https://[^\\s)\\]>\"'<]+");

  private static final Set<String> NOT_AN_EVENT_SITE =
      Set.of(
          "linkedin.com",
          "lnkd.in",
          "licdn.com",
          "x.com",
          "twitter.com",
          "facebook.com",
          "instagram.com",
          "youtube.com",
          "youtu.be",
          "tiktok.com");

  private static final String TITLE_SEPARATOR = " | ";

  private LinkedInPosts() {}

  static boolean isPost(CanonicalUrl url) {
    String host = url.getHost().toLowerCase(Locale.ROOT);
    String path = URI.create(url.getValue()).getPath();
    return (host.equals("linkedin.com") || host.endsWith(".linkedin.com"))
        && path != null
        && (path.startsWith("/posts/") || path.startsWith("/feed/update/"));
  }

  static Optional<URI> eventSite(String postText) {
    Map<String, URI> sites = new LinkedHashMap<>();
    Matcher m = LINK.matcher(postText);
    while (m.find()) {
      String raw = m.group().replaceAll("[.,;:!?]+$", "");
      CanonicalUrl canonical = CanonicalUrl.of(raw);
      URI uri;
      try {
        uri = URI.create(raw);
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (canonical != null
          && !isSocial(canonical.getHost())
          && PageScope.plainUrlProblem(uri) == null) {
        sites.putIfAbsent(canonical.getValue(), uri);
      }
    }
    return sites.size() == 1 ? Optional.of(sites.values().iterator().next()) : Optional.empty();
  }

  static String eventTitle(String postTitle) {
    int cut = postTitle.indexOf(TITLE_SEPARATOR);
    return (cut > 0 ? postTitle.substring(0, cut) : postTitle).strip();
  }

  private static boolean isSocial(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return NOT_AN_EVENT_SITE.stream().anyMatch(s -> h.equals(s) || h.endsWith("." + s));
  }
}
