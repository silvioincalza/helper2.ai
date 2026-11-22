package ai.helper2.sources.github.scout;

import ai.helper2.shared.CanonicalUrl;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * UC-030 BR-011, BR-013: the directory's pages of an item read in the pass that runs, so that a
 * page the follower of links read is not read again for its rating.
 */
public final class ItemPageReads {

  private final Map<CanonicalUrl, String> markdownOf = new ConcurrentHashMap<>();

  void passStarted() {
    markdownOf.clear();
  }

  void read(CanonicalUrl page, String markdown) {
    markdownOf.put(page, markdown);
  }

  @Nullable String markdownOf(CanonicalUrl page) {
    return markdownOf.get(page);
  }
}
