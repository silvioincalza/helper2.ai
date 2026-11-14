package ai.helper2.identity.usecase;

import ai.helper2.platform.BadRequestException;
import java.net.URI;

final class BookmarkRules {
  static final int MAX_PER_USER = 500;

  static final int MAX_URL_LENGTH = 2000;

  private BookmarkRules() {}

  static void requireWebUrl(URI url) {
    String scheme = url.getScheme();
    if (url.toString().length() > MAX_URL_LENGTH
        || scheme == null
        || !(scheme.equals("https") || scheme.equals("http"))) {
      throw new BadRequestException("invalid bookmark url");
    }
  }

  static void requireWithinCap(long total) {
    if (total > MAX_PER_USER) {
      throw new BadRequestException("At most " + MAX_PER_USER + " bookmarks per account.");
    }
  }
}
