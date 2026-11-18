package ai.helper2.identity.usecase;

import java.net.URI;

final class BookmarkRules {
  static final int MAX_PER_USER = 500;

  static final int MAX_URL_LENGTH = 2000;

  private BookmarkRules() {}

  static boolean isWebUrl(URI url) {
    String scheme = url.getScheme();
    return url.toString().length() <= MAX_URL_LENGTH
        && scheme != null
        && (scheme.equals("https") || scheme.equals("http"));
  }

  static boolean isOverTheCap(long total) {
    return total > MAX_PER_USER;
  }
}
