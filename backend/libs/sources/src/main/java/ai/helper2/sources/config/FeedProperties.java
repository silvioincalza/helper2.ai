package ai.helper2.sources.config;

import ai.helper2.search.SourceDescriptor;
import ai.helper2.shared.NewsFeeds;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public record FeedProperties(@Nullable List<Feed> feeds) {

  static final int MAX_ID = 40 - NewsFeeds.PREFIX.length();

  private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  public FeedProperties {
    feeds = feeds == null ? List.of() : List.copyOf(feeds);
    Set<String> ids = new HashSet<>();
    for (Feed f : feeds) {
      if (!ids.add(f.id())) {
        throw new IllegalArgumentException("helper2.feeds: duplicate id " + f.id());
      }
    }
  }

  public List<String> ids() {
    return feeds.stream().map(Feed::id).toList();
  }

  public Feed get(String id) {
    return feeds.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
  }

  public record Feed(
      String id,
      String publisher,
      String shortName,
      URI url,
      URI homepage,
      @Nullable List<String> requireWords,
      @Nullable Boolean enabled) {

    public Feed {
      if (id == null || !ID.matcher(id).matches() || id.length() > MAX_ID) {
        throw new IllegalArgumentException(
            "helper2.feeds: id '"
                + id
                + "' must be lower-case words with hyphens, at most "
                + MAX_ID
                + " characters");
      }
      if (publisher == null || publisher.isBlank()) {
        throw new IllegalArgumentException("helper2.feeds " + id + ": publisher is required");
      }
      if (shortName == null
          || shortName.isBlank()
          || shortName.length() > SourceDescriptor.SHORT_NAME_MAX) {
        throw new IllegalArgumentException(
            "helper2.feeds "
                + id
                + ": short-name needs 1 to "
                + SourceDescriptor.SHORT_NAME_MAX
                + " characters");
      }
      requireHttps(id, "url", url);
      requireHttps(id, "homepage", homepage);
      requireWords =
          requireWords == null
              ? List.of()
              : requireWords.stream()
                  .map(w -> w.strip().toLowerCase(Locale.ROOT))
                  .filter(w -> !w.isEmpty())
                  .toList();
      enabled = enabled == null || enabled;
    }

    public String sourceId() {
      return NewsFeeds.PREFIX + id;
    }

    public boolean isEnabled() {
      return Boolean.TRUE.equals(enabled);
    }

    private static void requireHttps(String id, String field, URI value) {
      if (value == null || !"https".equals(value.getScheme()) || value.getHost() == null) {
        throw new IllegalArgumentException(
            "helper2.feeds " + id + ": " + field + " must be an https URL");
      }
    }
  }
}
