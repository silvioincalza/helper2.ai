package ai.helper2.sources.support;

import java.util.Locale;
import java.util.Set;

/**
 * Sites that share copies of books and papers without the rights holder's permission. A web search
 * can return them for an AI book or paper; helper2.ai never lists or links them. A host matches
 * when one of its labels is a known name, so every mirror and language subdomain matches too
 * (ka.z-lib.fm, libgen.rs, annas-archive.se).
 */
public final class ShadowLibraries {

  private static final Set<String> NAMES =
      Set.of(
          "z-lib",
          "z-library",
          "zlibrary",
          "1lib",
          "singlelogin",
          "libgen",
          "sci-hub",
          "annas-archive",
          "pdfdrive");

  private ShadowLibraries() {}

  public static boolean isShadowLibrary(String host) {
    if (host == null || host.isBlank()) {
      return false;
    }
    String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
    for (int i = 0; i < labels.length - 1; i++) {
      if (NAMES.contains(labels[i])) {
        return true;
      }
    }
    return false;
  }
}
